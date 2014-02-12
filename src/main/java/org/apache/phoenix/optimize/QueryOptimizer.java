package org.apache.phoenix.optimize;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.phoenix.compile.ColumnProjector;
import org.apache.phoenix.compile.IndexStatementRewriter;
import org.apache.phoenix.compile.QueryCompiler;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.iterate.ParallelIterators.ParallelIteratorFactory;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.parse.HintNode;
import org.apache.phoenix.parse.HintNode.Hint;
import org.apache.phoenix.parse.ParseNodeFactory;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.parse.TableNode;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.ColumnNotFoundException;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PDatum;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableType;

import com.google.common.collect.Lists;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class QueryOptimizer {
    private static final ParseNodeFactory FACTORY = new ParseNodeFactory();

    private final QueryServices services;
    private final boolean useIndexes;

    public QueryOptimizer(QueryServices services) {
        this.services = services;
        this.useIndexes = this.services.getProps().getBoolean(QueryServices.USE_INDEXES_ATTRIB, QueryServicesOptions.DEFAULT_USE_INDEXES);
    }

    public QueryPlan optimize(SelectStatement select, PhoenixStatement statement) throws SQLException {
        return optimize(select, statement, Collections.<PColumn>emptyList(), null);
    }

    public QueryPlan optimize(SelectStatement select, PhoenixStatement statement, List<? extends PDatum> targetColumns, ParallelIteratorFactory parallelIteratorFactory) throws SQLException {
        QueryCompiler compiler = new QueryCompiler(statement, targetColumns, parallelIteratorFactory);
        QueryPlan dataPlan = compiler.compile(select);
        if (!useIndexes) {
            return dataPlan;
        }
        // Get the statement as it's been normalized now
        // TODO: the recompile for the index tables could skip the normalize step
        select = (SelectStatement)dataPlan.getStatement();
        PTable dataTable = dataPlan.getTableRef().getTable();
        List<PTable>indexes = Lists.newArrayList(dataTable.getIndexes());
        if (indexes.isEmpty() || dataPlan.getTableRef().hasDynamicCols() || select.getHint().hasHint(Hint.NO_INDEX)) {
            return dataPlan;
        }
        
        // The targetColumns is set for UPSERT SELECT to ensure that the proper type conversion takes place.
        // For a SELECT, it is empty. In this case, we want to set the targetColumns to match the projection
        // from the dataPlan to ensure that the metadata for when an index is used matches the metadata for
        // when the data table is used.
        if (targetColumns.isEmpty()) {
            List<? extends ColumnProjector> projectors = dataPlan.getProjector().getColumnProjectors();
            List<PDatum> targetDatums = Lists.newArrayListWithExpectedSize(projectors.size());
            for (ColumnProjector projector : projectors) {
                targetDatums.add(projector.getExpression());
            }
            targetColumns = targetDatums;
        }
        
        SelectStatement translatedIndexSelect = IndexStatementRewriter.translate(select, dataPlan.getContext().getResolver());
        List<QueryPlan> plans = Lists.newArrayListWithExpectedSize(1 + indexes.size());
        plans.add(dataPlan);
        QueryPlan hintedPlan = getHintedQueryPlan(statement, translatedIndexSelect, indexes, targetColumns, parallelIteratorFactory, plans);
        if (hintedPlan != null) {
            return hintedPlan;
        }
        for (PTable index : indexes) {
            addPlan(statement, translatedIndexSelect, index, targetColumns, parallelIteratorFactory, plans);
        }
        
        return chooseBestPlan(select, plans);
    }
    
    private static QueryPlan getHintedQueryPlan(PhoenixStatement statement, SelectStatement select, List<PTable> indexes, List<? extends PDatum> targetColumns, ParallelIteratorFactory parallelIteratorFactory, List<QueryPlan> plans) throws SQLException {
        QueryPlan dataPlan = plans.get(0);
        String indexHint = select.getHint().getHint(Hint.INDEX);
        if (indexHint == null) {
            return null;
        }
        int startIndex = 0;
        String alias = dataPlan.getTableRef().getTableAlias();
        String prefix = HintNode.PREFIX + (alias == null ? dataPlan.getTableRef().getTable().getName().getString() : alias) + HintNode.SEPARATOR;
        while (startIndex < indexHint.length()) {
            startIndex = indexHint.indexOf(prefix, startIndex);
            if (startIndex < 0) {
                return null;
            }
            startIndex += prefix.length();
            boolean done = false; // true when SUFFIX found
            while (startIndex < indexHint.length() && !done) {
                int endIndex;
                int endIndex1 = indexHint.indexOf(HintNode.SEPARATOR, startIndex);
                int endIndex2 = indexHint.indexOf(HintNode.SUFFIX, startIndex);
                if (endIndex1 < 0 && endIndex2 < 0) { // Missing SUFFIX shouldn't happen
                    endIndex = indexHint.length();
                } else if (endIndex1 < 0) {
                    done = true;
                    endIndex = endIndex2;
                } else if (endIndex2 < 0) {
                    endIndex = endIndex1;
                } else {
                    endIndex = Math.min(endIndex1, endIndex2);
                    done = endIndex2 == endIndex;
                }
                String indexName = indexHint.substring(startIndex, endIndex);
                int indexPos = getIndexPosition(indexes, indexName);
                if (indexPos >= 0) {
                    // Hinted index is applicable, so return it. It'll be the plan at position 1, after the data plan
                    if (addPlan(statement, select, indexes.get(indexPos), targetColumns, parallelIteratorFactory, plans)) {
                        return plans.get(1);
                    }
                    indexes.remove(indexPos);
                }
                startIndex = endIndex + 1;
            }
        }
        return null;
    }
    
    private static int getIndexPosition(List<PTable> indexes, String indexName) {
        for (int i = 0; i < indexes.size(); i++) {
            if (indexName.equals(indexes.get(i).getTableName().getString())) {
                return i;
            }
        }
        return -1;
    }
    
    private static boolean addPlan(PhoenixStatement statement, SelectStatement select, PTable index, List<? extends PDatum> targetColumns, ParallelIteratorFactory parallelIteratorFactory, List<QueryPlan> plans) throws SQLException {
        QueryPlan dataPlan = plans.get(0);
        int nColumns = dataPlan.getProjector().getColumnCount();
        String alias = '"' + dataPlan.getTableRef().getTableAlias() + '"'; // double quote in case it's case sensitive
        String schemaName = dataPlan.getTableRef().getTable().getSchemaName().getString();
        schemaName = schemaName.length() == 0 ? null :  '"' + schemaName + '"';

        String tableName = '"' + index.getTableName().getString() + '"';
        List<? extends TableNode> tables = Collections.singletonList(FACTORY.namedTable(alias, FACTORY.table(schemaName, tableName)));
        try {
            SelectStatement indexSelect = FACTORY.select(select, tables);            
            QueryCompiler compiler = new QueryCompiler(statement, targetColumns, parallelIteratorFactory);
            QueryPlan plan = compiler.compile(indexSelect);
            // Checking the index status and number of columns handles the wildcard cases correctly
            // We can't check the status earlier, because the index table may be out-of-date.
            if (plan.getTableRef().getTable().getIndexState() == PIndexState.ACTIVE && plan.getProjector().getColumnCount() == nColumns) {
                plans.add(plan);
                return true;
            }
        } catch (ColumnNotFoundException e) {
            /* Means that a column is being used that's not in our index.
             * Since we currently don't keep stats, we don't know the selectivity of the index.
             * For now, we just don't use this index (as opposed to trying to join back from
             * the index table to the data table.
             */
        }
        return false;
    }
    
    /**
     * Choose the best plan among all the possible ones.
     * Since we don't keep stats yet, we use the following simple algorithm:
     * 1) If the query has an ORDER BY and a LIMIT, choose the plan that has all the ORDER BY expression
     * in the same order as the row key columns.
     * 2) If there are more than one plan that meets (1), choose the plan with:
     *    a) the most row key columns that may be used to form the start/stop scan key.
     *    b) the plan that preserves ordering for a group by.
     *    c) the data table plan
     * @param plans the list of candidate plans
     * @return
     */
    private QueryPlan chooseBestPlan(SelectStatement select, List<QueryPlan> plans) {
        QueryPlan firstPlan = plans.get(0);
        if (plans.size() == 1) {
            return firstPlan;
        }
        
        /**
         * If we have a plan(s) that are just point lookups (i.e. fully qualified row
         * keys), then favor those first.
         */
        List<QueryPlan> candidates = Lists.newArrayListWithExpectedSize(plans.size());
        for (QueryPlan plan : plans) {
            if (plan.getContext().getScanRanges().isPointLookup()) {
                candidates.add(plan);
            }
        }
        /**
         * If we have a plan(s) that removes the order by, choose from among these,
         * as this is typically the most expensive operation. Once we have stats, if
         * there's a limit on the query, we might choose a different plan. For example
         * if the limit was a very large number and the combination of applying other 
         * filters on the row key are estimated to choose fewer rows, we'd choose that
         * one.
         */
        List<QueryPlan> stillCandidates = plans;
        List<QueryPlan> bestCandidates = candidates;
        if (!candidates.isEmpty()) {
            stillCandidates = candidates;
            bestCandidates = Lists.<QueryPlan>newArrayListWithExpectedSize(candidates.size());
        }
        for (QueryPlan plan : stillCandidates) {
            // If ORDER BY optimized out (or not present at all)
            if (plan.getOrderBy().getOrderByExpressions().isEmpty()) {
                bestCandidates.add(plan);
            }
        }
        if (bestCandidates.isEmpty()) {
            bestCandidates.addAll(stillCandidates);
        }
        final int comparisonOfDataVersusIndexTable = select.getHint().hasHint(Hint.USE_DATA_OVER_INDEX_TABLE) ? -1 : 1;
        Collections.sort(bestCandidates, new Comparator<QueryPlan>() {

            @Override
            public int compare(QueryPlan plan1, QueryPlan plan2) {
                int c = plan2.getContext().getScanRanges().getRanges().size() - plan1.getContext().getScanRanges().getRanges().size();
                if (c != 0) return c;
                if (plan1.getGroupBy()!=null && plan2.getGroupBy()!=null) {
                    if (plan1.getGroupBy().isOrderPreserving() != plan2.getGroupBy().isOrderPreserving()) {
                        return plan1.getGroupBy().isOrderPreserving() ? -1 : 1;
                    }
                }
                // Use smaller table (table with fewest kv columns)
                PTable table1 = plan1.getTableRef().getTable();
                PTable table2 = plan2.getTableRef().getTable();
                c = (table1.getColumns().size() - table1.getPKColumns().size()) - (table2.getColumns().size() - table2.getPKColumns().size());
                if (c != 0) return c;
                
                // All things being equal, just use the index table
                // TODO: have hint that drives this
                if (plan1.getTableRef().getTable().getType() == PTableType.INDEX) {
                    return comparisonOfDataVersusIndexTable;
                }
                if (plan2.getTableRef().getTable().getType() == PTableType.INDEX) {
                    return -comparisonOfDataVersusIndexTable;
                }
                
                return 0;
            }
            
        });
        
        return candidates.get(0);
        
    }

    
}

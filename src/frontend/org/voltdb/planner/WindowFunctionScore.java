/* This file is part of VoltDB.
 * Copyright (C) 2008-2020 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.WindowFunctionExpression;
import org.voltdb.types.SortDirectionType;

/**
 * A WindowFunctionScore keeps track of the score of
 * a single window function or else the statement level
 * order by expressions.
 */
class WindowFunctionScore {

    /**
     * When a match is attempted, it may match,
     * fail to match or else be a possible order spoiler.
     */
    enum MatchResults {
        /**
         * The attempt matched.
         */
        MATCHED,
        /**
         * The attempt did not match,
         * but this may be an order spoiler.
         */
        POSSIBLE_ORDER_SPOILER,
        /**
         * The score is dead or done.
         * So don't do anything else with
         * this score.
         */
        DONE_OR_DEAD
    }

    /**
     * A score can be dead, done or in progress.  Dead means
     * We have seen something we cannot match.  Done means we
     * have matched all the score's expressions.  In progress
     * means we are still matching expressions.
     */
    private enum MatchingState {
        INVALID,
        INPROGRESS,
        DEAD,
        DONE
    }

    // Set of active partition by expressions
    // for this window function.
    private List<ExpressionOrColumn> m_partitionByExprs = new ArrayList<>();
    // Sequence of expressions which
    // either match index expression or which have
    // single values.   These are from the partition
    // by list or else from the order by list.  At
    // the end this will be the concatenation of the
    // partition by list and the order by list.
    final List<AbstractExpression> m_orderedMatchingExpressions = new ArrayList<>();
    // List of order by expressions, originally from the
    // WindowFunctionExpression.  These migrate to
    // m_orderedMatchingExpressions as they match.
    private final List<ExpressionOrColumn> m_unmatchedOrderByExprs = new ArrayList<>();
    // This is the index of the unmatched expression
    // we will be working on.
    // This is the number of the window function.  It is
    // STATEMENT_LEVEL_ORDER_BY for the statement level order by list.
    int m_windowFunctionNumber;

    private MatchingState m_matchingState = MatchingState.INPROGRESS;
    // This is the sort direction for this window function
    // or order by list.
    private SortDirectionType m_sortDirection;
    // This is the set of bindings generated by matches in this
    // candidate.
    final List<AbstractExpression> m_bindings = new ArrayList<>();

    /**
     * A constructor for creating a score from a
     * WindowFunctionExpression.
     *
     * @param winfunc window function expression
     * @param winFuncNum This is the number of the window function.  It is
     *                   STATEMENT_LEVEL_ORDER_BY for the statement level order by list.
     */
    WindowFunctionScore(WindowFunctionExpression winfunc, int winFuncNum) {
        for (int idx = 0; idx < winfunc.getPartitionbySize(); idx += 1) {
            AbstractExpression ae = winfunc.getPartitionByExpressions().get(idx);
            m_partitionByExprs.add(new ExpressionOrColumn(-1, ae, SortDirectionType.INVALID));
        }
        for (int idx = 0; idx < winfunc.getOrderbySize(); idx += 1) {
            AbstractExpression ae = winfunc.getOrderByExpressions().get(idx);
            SortDirectionType sd = winfunc.getOrderByDirections().get(idx);
            m_unmatchedOrderByExprs.add(new ExpressionOrColumn(-1, ae, sd));
        }
        assert 0 <= winFuncNum;
        m_windowFunctionNumber = winFuncNum;
        m_sortDirection = SortDirectionType.INVALID;
    }

    /**
     * A constructor for creating a score from a
     * statement level order by expression.
     *
     * @param orderByExpressions The order by expressions.
     */
    WindowFunctionScore(List<ParsedColInfo> orderByExpressions) {
        for (ParsedColInfo pci : orderByExpressions) {
            SortDirectionType sortDir = pci.m_ascending ? SortDirectionType.ASC : SortDirectionType.DESC;
            m_unmatchedOrderByExprs.add(new ExpressionOrColumn(-1, pci.m_expression, sortDir));
        }
        // Statement level order by expressions are number STATEMENT_LEVEL_ORDER_BY_INDEX.
        m_windowFunctionNumber = WindowFunctionScoreboard.STATEMENT_LEVEL_ORDER_BY_INDEX;
        m_sortDirection = SortDirectionType.INVALID;
    }
    int getNumberMatches() {
        return m_orderedMatchingExpressions.size();
    }

    public boolean isDead() {
        return m_matchingState == MatchingState.DEAD;
    }

    public boolean isDone() {
        if (m_matchingState == MatchingState.DONE) {
            return true;
        } else if (m_partitionByExprs.isEmpty() && 0 == m_unmatchedOrderByExprs.size()) {
            markDone();
            // Settle on a sort direction.
            if (m_sortDirection == SortDirectionType.INVALID) {
                m_sortDirection = SortDirectionType.ASC;
            }
            return true;
        } else {
            return false;
        }
    }

    SortDirectionType sortDirection() {
        return m_sortDirection;
    }

    MatchResults matchIndexEntry(ExpressionOrColumn indexEntry) {
        // Don't bother to do anything if
        // we are dead or done.
        if (isDead() || isDone()) {
            return MatchResults.DONE_OR_DEAD;
        } else if ( ! m_partitionByExprs.isEmpty()) {
            // If there are more partition by expressions, then
            // find one which matches the indexEntry and move it
            // to the end of the ordered  matching expressions.
            List<AbstractExpression> moreBindings = null;
            for (ExpressionOrColumn eorc : m_partitionByExprs) {
                moreBindings = ExpressionOrColumn.findBindingsForOneIndexedExpression(eorc, indexEntry);
                if (moreBindings != null) {
                    // Good.  We matched.  Add the bindings.
                    // But we can't set the sort direction
                    // yet, because partition by doesn't
                    // care.  Note that eorc and indexEntry
                    // may not be equal.  They are just
                    // matching.  An expression in eorc
                    // might match a column reference in
                    // indexEntry, or eorc may have parameters
                    // which need to match expressions in indexEntry.
                    m_orderedMatchingExpressions.add(eorc.m_expr);
                    // If there are expressions later on in the
                    // m_partitionByExprs or m_unmatchedOrderByExprs
                    // which match this expression we need to
                    // delete them.  We can safely ignore them.
                    //
                    // If there are more than one instances of
                    // an expression, say with "PARTITION BY A, A",
                    // we need to remove them all.
                    m_partitionByExprs.removeAll(Collections.singleton(eorc));
                    m_bindings.addAll(moreBindings);
                    return MatchResults.MATCHED;
                }
            }
            // Mark this as dead.  We are not going
            // to manage order spoilers with window functions.
            markDead();
            return MatchResults.DONE_OR_DEAD;
        } else {
            // If there are no partition by expressions,
            // we need to look at the unmatched order by expressions.
            // These need to be AbstractExpressions.  But we may
            // match with a ColumnRef in the index.
            ExpressionOrColumn nextStatementEOC = m_unmatchedOrderByExprs.get(0);
            // If we have not settled on a sort direction
            // yet, we have to decide now.
            if (m_sortDirection == SortDirectionType.INVALID) {
                m_sortDirection = nextStatementEOC.sortDirection();
            }
            if (nextStatementEOC.sortDirection() != m_sortDirection) {
                // If the sort directions are not all
                // equal we can't use this index for this
                // candidate.  So just declare it dead.
                markDead();
                return MatchResults.DONE_OR_DEAD;
            }
            // NOTABENE: This is really the important part of
            //           this function.
            final List<AbstractExpression> moreBindings
                    = ExpressionOrColumn.findBindingsForOneIndexedExpression(nextStatementEOC, indexEntry);
            if (moreBindings != null) {
                // We matched, because moreBindings != null, and
                // the sort direction matched as well.  So
                // add nextEOC to the order matching expressions
                // list and add the bindings to the bindings list.
                m_orderedMatchingExpressions.add(nextStatementEOC.m_expr);
                m_bindings.addAll(moreBindings);
                // Remove the next statement EOC from the unmatched OrderByExpressions
                // list since we matched it.  We need to remove all of them,
                m_unmatchedOrderByExprs.removeAll(Collections.singleton(nextStatementEOC));
                return MatchResults.MATCHED;
            } else {
                // No Bindings were found.  Mark this as a
                // potential order spoiler if it's in a
                // statement level order by.  The index entry
                // number is in the ordinal number of the
                // expression or column reference in the index.
                assert 0 <= indexEntry.m_indexKeyComponentPosition;
                if (isWindowFunction()) {
                    // No order spoilers with window functions.
                    markDead();
                    return MatchResults.DONE_OR_DEAD;
                } else {
                    return MatchResults.POSSIBLE_ORDER_SPOILER;
                }
            }
        }
    }

    // Return true if this is a window function.  If it is a statement
    // level order by we return false.
    boolean isWindowFunction() {
        return 0 <= m_windowFunctionNumber;
    }

    void markDone() {
        assert m_matchingState == MatchingState.INPROGRESS;
        m_matchingState = MatchingState.DONE;
    }

    void markDead() {
        assert m_matchingState == MatchingState.INPROGRESS;
        m_matchingState = MatchingState.DEAD;
    }
}

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
package org.voltdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class StatsProcInputTable
{

    // A table of ProcInputRows: set of unique procedure names
    TreeSet<ProcInputRow> m_rowsTable = new TreeSet<ProcInputRow>();

    // A row for a procedure on a single host aggregating invocations and
    // min/max/avg bytes I/O across partitions
    static class ProcInputRow implements Comparable<ProcInputRow>
    {
        String procedure;
        long partition;
        long timestamp;
        long invocations;

        long minIN;
        long maxIN;
        long avgIN;


        // track which partitions and hosts have been witnessed.
        private final Set<Long> seenPartitions;

        public ProcInputRow(String procedure, long partition, long timestamp,
            long invocations, long minIN, long maxIN, long avgIN)
        {
            this.procedure = procedure;
            this.partition = partition;
            this.timestamp = timestamp;
            this.invocations = invocations;
            this.minIN = minIN;
            this.maxIN = maxIN;
            this.avgIN = avgIN;

            seenPartitions = new TreeSet<Long>();
            seenPartitions.add(partition);

        }

        @Override
        public int compareTo(ProcInputRow other)
        {
            return procedure.compareTo(other.procedure);

        }

        // Augment this ProcInputRow with a new input row
        // dedup flag indicates if we should dedup data based on partition for proc.
        void updateWith(boolean dedup, ProcInputRow in)        {
            // adjust the avg across all replicas.
            this.avgIN = calculateAverage(this.avgIN, this.invocations,
                in.avgIN, in.invocations);
            this.minIN = Math.min(this.minIN, in.minIN);
            this.maxIN = Math.max(this.maxIN, in.maxIN);

            if (!dedup) {
                //Not deduping so add up all values.
                this.invocations += in.invocations;
            } else {
                if (!seenPartitions.contains(in.partition)) {
                    this.invocations += in.invocations;
                    seenPartitions.add(in.partition);
                }
            }
        }
    }

    /**
     * Given a running average and the running invocation total as well as a new
     * row's average and invocation total, return a new running average
     */
    static long calculateAverage(long currAvg, long currInvoc, long rowAvg, long rowInvoc)
    {
        long currTtl = currAvg * currInvoc;
        long rowTtl = rowAvg * rowInvoc;

        // If both are 0, then currTtl, rowTtl are also 0.
        if ((currInvoc + rowInvoc) == 0L) {
            return 0L;
        } else {
            return (currTtl + rowTtl) / (currInvoc + rowInvoc);
        }
    }

    /**
     * Safe division that assumes x/0 = 100%
     */
    static long calculatePercent(long num, long denom)
    {
        if (denom == 0L) {
            return 100L;
        } else {
            return Math.round(100.0 * num / denom);
        }
    }

    // Sort by total bytes out
    public int compareByInput(ProcInputRow r1, ProcInputRow r2)
    {
        if (r1.avgIN * r1.invocations > r2.avgIN * r2.invocations) {
            return 1;
        } else if (r1.avgIN * r1.invocations < r2.avgIN * r2.invocations) {
            return -1;
        } else {
            return 0;
        }
    }

    // Add or update the corresponding row. dedup flag indicates if we should dedup data based on partition for proc.
    public void updateTable(boolean dedup, String procedure, long partition, long timestamp,
            long invocations, long minIN, long maxIN, long avgIN)
    {
        ProcInputRow in = new ProcInputRow(procedure, partition, timestamp,
            invocations, minIN, maxIN, avgIN);
        ProcInputRow exists = m_rowsTable.ceiling(in);
        if (exists != null && in.procedure.equals(exists.procedure)) {
            exists.updateWith(dedup, in);
        } else {
            m_rowsTable.add(in);
        }
    }

    // Return table ordered by total bytes out
    public VoltTable sortByInput(String tableName)
    {
        List<ProcInputRow> sorted = new ArrayList<ProcInputRow>(m_rowsTable);
        Collections.sort(sorted, new Comparator<ProcInputRow>() {
            @Override
            public int compare(ProcInputRow r1, ProcInputRow r2) {
                return compareByInput(r2, r1); // sort descending
            }
        });

        long totalInput = 0L;
        for (ProcInputRow row : sorted) {
            totalInput += (row.avgIN * row.invocations);
        }

        int kB = 1024;
        int mB = 1024 * kB;

        VoltTable result = TableShorthand.tableFromShorthand(
            tableName +
            "(TIMESTAMP:BIGINT," +
                "PROCEDURE:VARCHAR," +
                "WEIGHTED_PERC:BIGINT," +
                "INVOCATIONS:BIGINT," +
                "MIN_PARAMETER_SET_SIZE:BIGINT," +
                "MAX_PARAMETER_SET_SIZE:BIGINT," +
                "AVG_PARAMETER_SET_SIZE:BIGINT," +
                "TOTAL_PARAMETER_SET_SIZE_MB:BIGINT)"
                );

        for (ProcInputRow row : sorted) {
            result.addRow(
                row.timestamp,
                row.procedure,
                calculatePercent((row.avgIN * row.invocations), totalInput), //% total in
                row.invocations,
                row.minIN,
                row.maxIN,
                row.avgIN,
                (row.avgIN * row.invocations) / mB //total in
                );
        }
        return result;
    }
}

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

package org.voltdb.exportclient.loopback;

import java.util.Arrays;
import java.util.IllegalFormatConversionException;
import java.util.MissingFormatArgumentException;
import java.util.UnknownFormatConversionException;

public class LoopbackExportException extends RuntimeException {

    private static final long serialVersionUID = 7463238476427297892L;

    public LoopbackExportException() {
    }

    public LoopbackExportException(String format, Object...args) {
        super(format(format, args));
    }

    public LoopbackExportException(Throwable cause) {
        super(cause);
    }

    public LoopbackExportException(String format, Throwable cause, Object...args) {
        super(format(format, args), cause);
    }

    static protected String format(String format, Object...args) {
        String formatted = null;
        try {
            formatted = String.format(format, args);
        } catch (MissingFormatArgumentException|IllegalFormatConversionException|
                UnknownFormatConversionException ignoreThem) {
        }
        finally {
            if (formatted == null) {
                formatted = "Format: " + format + ", arguments: " + Arrays.toString(args);
            }
        }
        return formatted;
    }
}

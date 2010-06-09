/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.util.Properties;

import org.voltdb.client.Client;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

public class HTTPClientInterface {

    Client m_client = null;

    public String process(String uri, String method, Properties header, Properties parms) {
        String msg;

        try {
            if (m_client == null) {
                m_client = ClientFactory.createClient();
                m_client.createConnection("localhost", null, null);
            }

            //String username = parms.getProperty("User");
            //String password = parms.getProperty("Password");
            String procName = parms.getProperty("Procedure");
            String params = parms.getProperty("Parameters");

            ClientResponse response = null;

            if (params != null) {
                ParameterSet paramSet = ParameterSet.fromJSONString(params);
                response = m_client.callProcedure(procName, paramSet.toArray());
            }
            else {
                response = m_client.callProcedure(procName);
            }

            ClientResponseImpl rimpl = (ClientResponseImpl) response;
            msg = rimpl.toJSONString();
        }
        catch (Exception e) {
            msg = e.getMessage();
            e.printStackTrace();
            m_client = null;
        }

        return msg;
    }
}

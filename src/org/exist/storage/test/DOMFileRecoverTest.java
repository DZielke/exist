/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-04 The eXist Team
 *
 *  http://exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.storage.test;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.List;

import junit.framework.TestCase;
import junit.textui.TestRunner;

import org.exist.security.SecurityManager;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.NativeBroker;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Value;
import org.exist.storage.dom.DOMFile;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.Configuration;

/**
 * Tests transaction management  and basic recovery for the DOMFile class.
 * 
 * @author wolf
 *
 */
public class DOMFileRecoverTest extends TestCase {

	public static void main(String[] args) {
		TestRunner.run(DOMFileRecoverTest.class);
	}
	
	private BrokerPool pool;
	
	public void testAdd() throws Exception {
        System.out.println("Add some random data and force db corruption ...\n");
        
		TransactionManager mgr = pool.getTransactionManager();
		DBBroker broker = null;
		try {
			broker = pool.get(SecurityManager.SYSTEM_USER);
            broker.flush();
			Txn txn = mgr.beginTransaction();
			System.out.println("Transaction started ...");
            
            DOMFile domDb = ((NativeBroker) broker).getDOMFile();
            domDb.setOwnerObject(this);
            
            BrokerPool.FORCE_CORRUPTION = true;
            
            // put 1000 values into the btree
            long firstToRemove = -1;
            for (int i = 1; i <= 10000; i++) {
                byte[] data = ("Value" + i).getBytes();
                long addr = domDb.put(txn, new NativeBroker.NodeRef(500, i), data);
                if (i == 1)
                    firstToRemove = addr;
            }

            domDb.closeDocument();
            
            // remove all
            NativeBroker.NodeRef ref = new NativeBroker.NodeRef(500);
            IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
            domDb.remove(txn, idx, null);
            domDb.removeAll(txn, firstToRemove);
            
            // put some more
            for (int i = 1; i <= 10000; i++) {
                byte[] data = ("Value" + i).getBytes();
                long addr = domDb.put(txn, new NativeBroker.NodeRef(500, i), data);
            }
            
            domDb.closeDocument();
            mgr.commit(txn);
            
            txn = mgr.beginTransaction();
            
            // put 1000 new values into the btree
            for (int i = 1; i <= 1000; i++) {
                byte[] data = ("Value" + i).getBytes();
                long addr = domDb.put(txn, new NativeBroker.NodeRef(501, i), data);
                if (i == 1)
                    firstToRemove = addr;
            }
            domDb.closeDocument();
            mgr.commit(txn);
            
            // the following transaction is not committed and will be rolled back during recovery
            txn = mgr.beginTransaction();
            
            for (int i = 1; i <= 200; i++) {
                domDb.remove(txn, new NativeBroker.NodeRef(500, i));
            }

            idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, new NativeBroker.NodeRef(501));
            domDb.remove(txn, idx, null);
            domDb.removeAll(txn, firstToRemove);
            
            mgr.getLogManager().flushToLog(true);
            
            Writer writer = new StringWriter();
            domDb.dump(writer);
            System.out.println(writer.toString());
		} finally {
			pool.release(broker);
		}
	}
	
    public void testGet() throws Exception {
        System.out.println("Recover and read the data ...\n");
        TransactionManager mgr = pool.getTransactionManager();
        DBBroker broker = null;
        try {
            broker = pool.get(SecurityManager.SYSTEM_USER);
            
            DOMFile domDb = ((NativeBroker) broker).getDOMFile();
            domDb.setOwnerObject(this);
            
            IndexQuery query = new IndexQuery(IndexQuery.GT, new NativeBroker.NodeRef(500));
            List keys = domDb.findKeys(query);
            int count = 0;
            for (Iterator i = keys.iterator(); i.hasNext(); count++) {
                Value key = (Value) i.next();
                Value value = domDb.get(key);
                System.out.println(new String(value.data(), value.start(), value.getLength()));
            }
            System.out.println("Values read: " + count);
            
            Writer writer = new StringWriter();
            domDb.dump(writer);
            System.out.println(writer.toString());
        } finally {
            pool.release(broker);
        }
    }
    
	protected void setUp() throws Exception {
		String home, file = "conf.xml";
        home = System.getProperty("exist.home");
        if (home == null)
            home = System.getProperty("user.dir");
        try {
            Configuration config = new Configuration(file, home);
            BrokerPool.configure(1, 5, config);
			pool = BrokerPool.getInstance();
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
	}

	protected void tearDown() throws Exception {
		BrokerPool.stopAll(false);
	}
}

/*
 * CollectionStore.java - Jun 19, 2003
 * 
 * @author wolf
 */
package org.exist.storage.index;

import java.io.File;

import org.exist.storage.BrokerPool;
import org.exist.storage.CacheManager;
import org.exist.storage.NativeBroker;
import org.exist.storage.btree.DBException;

/**
 * Handles access to the central collection storage file (collections.dbx). 
 * 
 * @author wolf
 */
public class CollectionStore extends BFile {

	/**
	 * @param file
	 * @param btreeBuffers
	 * @param dataBuffers
	 * @throws DBException 
	 */
	public CollectionStore(BrokerPool pool, File file, CacheManager cacheManager) throws DBException {
		super(pool, NativeBroker.COLLECTIONS_DBX_ID, file, cacheManager, 1.25, 100, 1000);
	}
	
	
    /* (non-Javadoc)
     * @see org.dbxml.core.filer.BTree#getBTreeSyncPeriod()
     */
    protected long getBTreeSyncPeriod() {
        return 1000;
    }
    
    
    /* (non-Javadoc)
     * @see org.exist.storage.store.BFile#getDataSyncPeriod()
     */
    protected long getDataSyncPeriod() {
        return 1000;
    }
    
    public boolean flush() throws DBException {
        if (!BrokerPool.FORCE_CORRUPTION) {
            dataCache.flush();
            super.flush();
        }
        return true;
    }
}

/*
 * CollectionStore.java - Jun 19, 2003
 * 
 * @author wolf
 */
package org.exist.storage.index;

import java.io.File;
import java.io.IOException;
import java.io.Writer;

import org.exist.storage.BrokerPool;
import org.exist.storage.CacheManager;
import org.exist.storage.NativeBroker;
import org.exist.storage.btree.DBException;
import org.exist.storage.btree.Value;
import org.exist.util.ByteConversion;

/**
 * Handles access to the central collection storage file (collections.dbx). 
 * 
 * @author wolf
 */
public class CollectionStore extends BFile {
    
    public final static String FREE_DOC_ID_KEY = "__free_doc_id";
    public final static String NEXT_DOC_ID_KEY = "__next_doc_id";  
    public final static String FREE_COLLECTION_ID_KEY = "__free_collection_id";
    public final static String NEXT_COLLECTION_ID_KEY = "__next_collection_id";  
    
    /**
     * 
     * 
     * @param pool 
     * @param cacheManager 
     * @param file 
     * @throws DBException 
     */
	public CollectionStore(BrokerPool pool, File file, CacheManager cacheManager) throws DBException {
		super(pool, NativeBroker.COLLECTIONS_DBX_ID, true, file, cacheManager, 1.25, 0.01, 0.03);
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
    	boolean flushed = false;
        if (!BrokerPool.FORCE_CORRUPTION) {
            flushed = flushed | dataCache.flush();
            flushed = flushed | super.flush();
        }
        return flushed;
    }
    
    protected void dumpValue(Writer writer, Value value) throws IOException {
        if (value.getLength() == 7) {
            short collectionId = ByteConversion.byteToShort(value.data(), value.start());
            int docId = ByteConversion.byteToInt(value.data(), value.start() + 3);
            writer.write('[');
            writer.write("Document: collection = ");
            writer.write(collectionId);
            writer.write(", docId = ");
            writer.write(docId);
            writer.write(']');
        } else {
            writer.write('[');
            writer.write("Collection: ");
            writer.write(new String(value.data(), value.start(), value.getLength(), "UTF-8"));
            writer.write(']');
        }
    }
}

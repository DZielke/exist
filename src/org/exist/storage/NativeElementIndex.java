/*
 * eXist Open Source Native XML Database 
 * Copyright (C) 2001-06 The eXist Project
 * 
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Library General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any
 * later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Library General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Library General Public License
 * along with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * 
 * $Id$
 */
package org.exist.storage;

import org.apache.log4j.Logger;
import org.exist.EXistException;
import org.exist.numbering.DLN;
import org.exist.numbering.NodeId;
import org.exist.collections.Collection;
import org.exist.dom.*;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.User;
import org.exist.security.xacml.AccessContext;
import org.exist.storage.btree.BTreeException;
import org.exist.storage.btree.DBException;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Value;
import org.exist.storage.index.BFile;
import org.exist.storage.io.VariableByteArrayInput;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.lock.Lock;
import org.exist.util.*;
import org.exist.xquery.Constants;
import org.exist.xquery.Expression;
import org.exist.xquery.NodeSelector;
import org.exist.xquery.TerminatedException;
import org.exist.xquery.XQueryContext;
import org.w3c.dom.Node;

import java.io.EOFException;
import java.io.IOException;
import java.util.*;

/** The indexing occurs in this class. That is, during the loading of a document
 * into the database, the process of associating a long gid with each element,
 * and the subsequent storing of the {@link NodeProxy} on disk.
 */
public class NativeElementIndex extends ElementIndex implements ContentLoadingObserver {
    
	private final static byte ENTRIES_ORDERED = 0;
	private final static byte ENTRIES_UNORDERED = 1;
	
    private static Logger LOG = Logger.getLogger(NativeElementIndex.class.getName());

    /** The datastore for this node index */
    protected BFile dbNodes;

    /** Work output Stream taht should be cleared before every use */
    private VariableByteOutputStream os = new VariableByteOutputStream();
    
    public NativeElementIndex(DBBroker broker, BFile dbNodes) {
        super(broker);
        this.dbNodes = dbNodes;
    }
 
    /** Store the given node in the node index.
     * @param qname The node's identity
     * @param proxy     The node's proxy
     */
    public void addNode(QName qname, NodeProxy proxy) {      
    	if (doc.getDocId() != proxy.getDocument().getDocId()) {
    		throw new IllegalArgumentException("Document id ('" + doc.getDocId() + "') and proxy id ('" + 
    				proxy.getDocument().getDocId() + "') differ !");
    	}
        //Is this qname already pending ?
        ArrayList buf = (ArrayList) pending.get(qname);
        if (buf == null) {
            //Create a node list
            buf = new ArrayList(50);
            pending.put(qname, buf);
        }
        //Add node's proxy to the list
        buf.add(proxy);
    }
    
    public void storeAttribute(AttrImpl node, NodePath currentPath, boolean fullTextIndexSwitch) {
        // TODO Auto-generated method stub      
    }

    public void storeText(TextImpl node, NodePath currentPath, boolean fullTextIndexSwitch) {
        // TODO Auto-generated method stub      
    }

    public void startElement(ElementImpl impl, NodePath currentPath, boolean index) {
        // TODO Auto-generated method stub      
    }

    public void endElement(int xpathType, ElementImpl node, String content) {
        // TODO Auto-generated method stub      
    }

    public void removeElement(ElementImpl node, NodePath currentPath, String content) {
        // TODO Auto-generated method stub      
    }    
    
    /* (non-Javadoc)
     * @see org.exist.storage.ContentLoadingObserver#sync()
     */
    public void sync() {
        final Lock lock = dbNodes.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            dbNodes.flush();
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);
            //TODO : throw an exception ? -pb
        } catch (DBException e) {
            LOG.error(e.getMessage(), e); 
            //TODO : throw an exception ? -pb
        } finally {
            lock.release();
        }
    }    


    /* (non-Javadoc)
     * @see org.exist.storage.ContentLoadingObserver#flush()
     */
    public void flush() {
        //TODO : return if doc == null? -pb
        if (pending.size() == 0) 
            return;
        final ProgressIndicator progress = new ProgressIndicator(pending.size(), 5); 
        final short collectionId = this.doc.getCollection().getId(); 
        final Lock lock = dbNodes.getLock();   
        int count = 0;
        for (Iterator i = pending.entrySet().iterator(); i.hasNext(); count++) {
            Map.Entry entry = (Map.Entry) i.next();
            QName qname = (QName) entry.getKey();
            //TODO : NativeValueIndex uses LongLinkedLists -pb
            ArrayList gids = (ArrayList) entry.getValue();            
            int gidsCount = gids.size();
            //Don't forget this one
            FastQSort.sort(gids, 0, gidsCount - 1);
            os.clear();
            os.writeInt(this.doc.getDocId());
            os.writeByte(inUpdateMode ? ENTRIES_UNORDERED : ENTRIES_ORDERED);
            
            os.writeInt(gidsCount);
            //TOUNDERSTAND -pb
            int lenOffset = os.position();
            os.writeFixedInt(0);  
            //Compute the GIDs list
            for (int j = 0; j < gidsCount; j++) {
                NodeProxy storedNode = (NodeProxy) gids.get(j);
                if (doc.getDocId() != storedNode.getDocument().getDocId()) {
                    throw new IllegalArgumentException("Document id ('" + doc.getDocId() + "') and proxy id ('" + 
                            storedNode.getDocument().getDocId() + "') differ !");
                }
                try {
                    storedNode.getNodeId().write(os);
                } catch (IOException e) {
                    LOG.warn("IO error while writing structural index: " + e.getMessage(), e);
                }
                StorageAddress.write(storedNode.getInternalAddress(), os);
            }
            broker.getBrokerPool().getNodeFactory().writeEndOfDocument(os);
            os.writeFixedInt(lenOffset, os.position() - lenOffset - 4);
            try {
                lock.acquire(Lock.WRITE_LOCK);
                //Store the data
                final Value key = computeKey(collectionId, qname);
                if (dbNodes.append(key, os.data()) == BFile.UNKNOWN_ADDRESS) {
                    LOG.error("Could not put index data for node '" +  qname + "'"); 
                }
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);
                //TODO : return ?
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);   
                //TODO : return ?
            } catch (ReadOnlyException e) {
                LOG.warn("Read-only error on '" + dbNodes.getFile().getName() + "'", e);
                //Return without clearing the pending entries
                return;                 
            } finally {
                lock.release();
            }
            progress.setValue(count);
            if (progress.changed()) {
                setChanged();
                notifyObservers(progress);
            }            
        }        
        progress.finish();
        setChanged();
        notifyObservers(progress);
        pending.clear();
        inUpdateMode = false;
    }    
    
    public void remove() {      
        //TODO : return if doc == null? -pb  
        if (pending.size() == 0) 
            return; 
        final short collectionId = this.doc.getCollection().getId();
        final Lock lock = dbNodes.getLock();
        for (Iterator i = pending.entrySet().iterator(); i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            List storedGIDList = (ArrayList) entry.getValue();
            QName qname = (QName) entry.getKey();
            final Value key = computeKey(collectionId, qname);     
            List newGIDList = new ArrayList();
            os.clear();             
            try {
                lock.acquire(Lock.WRITE_LOCK);
                Value value = dbNodes.get(key);
                //Does the node already exist in the index ?
                if (value != null) {
                    //Add its data to the new list                    
                    VariableByteArrayInput is = new VariableByteArrayInput(value.getData());
                    try {
                        while (is.available() > 0) {
                            int storedDocId = is.readInt();
                            byte isOrdered = is.readByte();
                            int gidsCount = is.readInt();
                            //TOUNDERSTAND -pb
                            int size = is.readFixedInt();
                            if (storedDocId != this.doc.getDocId()) {
                                // data are related to another document:
                                // append them to any existing data
                                os.writeInt(storedDocId);
                                os.writeByte(isOrdered);
                                os.writeInt(gidsCount);
                                os.writeFixedInt(size);
                                try {
                                    is.copyRaw(os, size);
                                } catch(EOFException e) {
                                    LOG.error(e.getMessage(), e);
                                    //TODO : data will be saved although os is probably corrupted ! -pb
                                }
                            } else {
                                // data are related to our document:
                                // feed the new list with the GIDs
                                for (int j = 0; j < gidsCount; j++) {
                                	NodeId nodeId = 
                                		broker.getBrokerPool().getNodeFactory().createFromStream(is);                                        
                                    long address = StorageAddress.read(is);
                                    // add the node to the new list if it is not 
                                    // in the list of removed nodes
                                    if (!containsNode(storedGIDList, nodeId)) {
                                        newGIDList.add(new NodeProxy(doc, nodeId, address));
                                    }
                                }
                                broker.getBrokerPool().getNodeFactory().createFromStream(is);
                            }
                        }
                    } catch (EOFException e) {
                        //TODO : remove this block if unexpected -pb
                        LOG.warn("REPORT ME " + e.getMessage(), e);
                    }
                    //append the data from the new list
                    if (newGIDList.size() > 0 ) {                        
                        int gidsCount = newGIDList.size();
                        //Don't forget this one
                        FastQSort.sort(newGIDList, 0, gidsCount - 1);                
                        os.writeInt(this.doc.getDocId());
                        os.writeByte(ENTRIES_ORDERED);
                        os.writeInt(gidsCount);
                        //TOUNDERSTAND -pb
                        int lenOffset = os.position();
                        os.writeFixedInt(0);
                        for (int j = 0; j < gidsCount; j++) {
                            NodeProxy storedNode = (NodeProxy) newGIDList.get(j);
                            if (doc.getDocId() != storedNode.getDocument().getDocId()) {
                                throw new IllegalArgumentException("Document id ('" + doc.getDocId() + "') and proxy id ('" + 
                                        storedNode.getDocument().getDocId() + "') differ !");
                            }
                            try {
                                storedNode.getNodeId().write(os);
                            } catch (IOException e) {
                                LOG.warn("IO error while writing structural index: " + e.getMessage(), e);
                            }
                            StorageAddress.write(storedNode.getInternalAddress(), os);
                        }
                        broker.getBrokerPool().getNodeFactory().writeEndOfDocument(os);
                        os.writeFixedInt(lenOffset, os.position() - lenOffset - 4);    
                    }
                }                
                //Store the data
                if (value == null) {
                    if (dbNodes.put(key, os.data()) == BFile.UNKNOWN_ADDRESS) {
                        LOG.error("Could not put index data for node '" +  qname + "'");  
                    }                    
                } else {
                    if (dbNodes.update(value.getAddress(), key, os.data()) == BFile.UNKNOWN_ADDRESS) {
                        LOG.error("Could not put index data for node '" +  qname + "'");  
                    }                    
                }
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);                
            } catch (ReadOnlyException e) {
                LOG.warn("Read-only error on '" + dbNodes.getFile().getName() + "'", e);   
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
            } finally {
                lock.release();
            }
        }        
        pending.clear();
    } 
    
    /* Drop all index entries for the given collection.
     * @see org.exist.storage.ContentLoadingObserver#dropIndex(org.exist.collections.Collection)
     */
    public void dropIndex(Collection collection) {        
        final Value ref = new ElementValue(collection.getId());
        final IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        final Lock lock = dbNodes.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            //TODO : flush ? -pb
            dbNodes.removeAll(null, query);
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);
        } catch (BTreeException e) {
            LOG.error(e.getMessage(), e);
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        } finally {
            lock.release();
        }
    }    
    
    /* Drop all index entries for the given document.
     * @see org.exist.storage.ContentLoadingObserver#dropIndex(org.exist.dom.DocumentImpl)
     */
    //TODO : note that this is *not* this.doc -pb
    public void dropIndex(DocumentImpl document) throws ReadOnlyException {              
        final short collectionId = document.getCollection().getId();
        final Value ref = new ElementValue(collectionId);
        final IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        final Lock lock = dbNodes.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            ArrayList elements = dbNodes.findKeys(query);  
            for (int i = 0; i < elements.size(); i++) {
                boolean changed = false;
                Value key = (Value) elements.get(i);
                VariableByteInput is = dbNodes.getAsStream(key);
                os.clear();  
                try {              
                    while (is.available() > 0) {
                        int storedDocId = is.readInt();
                        byte ordered = is.readByte();
                        int gidsCount = is.readInt();
                        //TOUNDERSTAND -pb
                        int size = is.readFixedInt();
                        if (storedDocId != document.getDocId()) {
                            // data are related to another document:
                            // copy them to any existing data
                            os.writeInt(storedDocId);
                            os.writeByte(ordered);
                            os.writeInt(gidsCount);
                            os.writeFixedInt(size);
                            is.copyRaw(os, size);
                        } else {
                            // data are related to our document:
                            // skip them          
                            changed = true;
                            is.skipBytes(size);
                        }
                    }
                } catch (EOFException e) {
                   //EOF is expected here 
                }                
                if (changed) {  
                    //TODO : no call to dbNodes.remove if no data ? -pb
                    //TODO : why not use the same construct as above :
                    //dbNodes.update(value.getAddress(), ref, os.data()) -pb
                    if (dbNodes.put(key, os.data()) == BFile.UNKNOWN_ADDRESS) {
                        LOG.error("Could not put index data for value '" +  ref + "'");
                    }                    
                }
            }
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);
        } catch (TerminatedException e) {
            LOG.warn(e.getMessage(), e);  
        } catch (BTreeException e) {
            LOG.error(e.getMessage(), e);
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        } finally {
            lock.release();
        }
        if (os.size() > 512000)
            // garbage collect the output stream if it is larger than 512k, otherwise reuse it
            os = new VariableByteOutputStream();
    }


    /* (non-Javadoc)
     * @see org.exist.storage.ContentLoadingObserver#reindex(org.exist.dom.DocumentImpl, org.exist.dom.NodeImpl)
     */
    //TODO : note that this is *not* this.doc -pb
    public void reindex(DocumentImpl document, StoredNode node) {
    }

    /**
     * Lookup elements or attributes in the index matching a given {@link QName} and
     * {@link NodeSelector}. The NodeSelector argument is optional. If selector is
     * null, all elements or attributes matching qname will be returned.
     * 
     * @param type either {@link ElementValue#ATTRIBUTE}, {@link ElementValue#ELEMENT}
     *      or {@link ElementValue#ATTRIBUTE_ID}
     * @param docs the set of documents to look up in the index
     * @param qname the QName of the attribute or element
     * @param selector an (optional) NodeSelector
     */
    public NodeSet findElementsByTagName(byte type, DocumentSet docs, QName qname, NodeSelector selector) {
        short nodeType = getIndexType(type);
        final ExtArrayNodeSet result = new ExtArrayNodeSet(docs.getLength(), 256);
        final Lock lock = dbNodes.getLock();
        // true if the output document set is the same as the input document set
        boolean sameDocSet = true;
        for (Iterator i = docs.getCollectionIterator(); i.hasNext();) {
            //Compute a key for the node
            Collection collection = (Collection) i.next();
            short collectionId = collection.getId();
            final Value key = computeTypedKey(type, collectionId, qname);
            try {
                lock.acquire(Lock.READ_LOCK);
                VariableByteInput is = dbNodes.getAsStream(key); 
                //Does the node already has data in the index ?
                if (is == null) {
                	sameDocSet = false;
                    continue;
                }
                while (is.available() > 0) {
                    int storedDocId = is.readInt();
                    byte ordered = is.readByte();
                    int gidsCount = is.readInt();
                    //TOUNDERSTAND -pb
                    int size = is.readFixedInt();
                    DocumentImpl storedDocument = docs.getDoc(storedDocId);
                    //Exit if the document is not concerned
                    if (storedDocument == null) {
                        is.skipBytes(size);
                        continue;
                    }               
                    //Process the nodes
                    NodeId nodeId;
                    for (int k = 0; k < gidsCount; k++) {
                        nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                        long address = StorageAddress.read(is);
                        if (selector == null) {
                            NodeProxy storedNode = new NodeProxy(storedDocument, nodeId, nodeType, address);
                            result.add(storedNode, gidsCount);                        
                        } else {
                            //Filter out the node if requested to do so
                            NodeProxy storedNode = selector.match(storedDocument, nodeId);
                            if (storedNode != null) {
                                storedNode.setInternalAddress(address);
                                storedNode.setNodeType(nodeType);
                                result.add(storedNode, gidsCount);
                            } else
                            	sameDocSet = false;
                        }
                    }
                    nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                    result.setSorted(storedDocument, ordered == ENTRIES_ORDERED);
                }
            } catch (EOFException e) {
                //EOFExceptions are expected here
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);               
                //TODO : return ?
            } finally {
                lock.release();
            }
        }
//        LOG.debug("Found: " + result.getLength() + " for " + qname);
        if (sameDocSet) {
        	result.setDocumentSet(docs);
        }
        return result;
    }

    /**
     * Optimized lookup method which directly implements the ancestor-descendant join. The algorithm
     * does directly operate on the input stream containing the potential descendant nodes. It thus needs
     * less comparisons than {@link #findElementsByTagName(byte, DocumentSet, QName, NodeSelector)}.
     * 
     * @param type either {@link ElementValue#ATTRIBUTE}, {@link ElementValue#ELEMENT}
     *      or {@link ElementValue#ATTRIBUTE_ID}
     * @param docs the set of documents to look up in the index
     * @param contextSet the set of ancestor nodes for which the method will try to find descendants
     * @param contextId id of the current context expression as passed by the query engine
     * @param qname the QName to search for
     */
    public NodeSet findDescendantsByTagName(byte type, QName qname, int axis,
    		DocumentSet docs, ExtArrayNodeSet contextSet,  int contextId) {
//        LOG.debug(contextSet.toString());
        short nodeType = getIndexType(type);
        ByDocumentIterator citer = contextSet.iterateByDocument();
        final ExtArrayNodeSet result = new ExtArrayNodeSet(docs.getLength(), 256);
        final Lock lock = dbNodes.getLock();
        // true if the output document set is the same as the input document set
        boolean sameDocSet = true;
        for (Iterator i = docs.getCollectionIterator(); i.hasNext();) {
            //Compute a key for the node
            Collection collection = (Collection) i.next();
            short collectionId = collection.getId();
            final Value key = computeTypedKey(type, collectionId, qname);
            try {
                lock.acquire(Lock.READ_LOCK);
                VariableByteInput is;
                /*
                //TODO : uncomment an implement properly
                //TODO : bewere of null NS prefix : it looks to be polysemic (none vs. all)
                //Test for "*" prefix
                if (qname.getPrefix() == null) {
                	try {
	                    final IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, key);
	                    ArrayList elements = dbNodes.findKeys(query);	                     
                    } catch (BTreeException e) {
                        LOG.error(e.getMessage(), e);
                        //TODO : throw an exception ? -pb
                    } catch (TerminatedException e) {
                        LOG.warn(e.getMessage(), e);                        
                    }
                    //TODO : iterate over the keys 
                } else */                
                	is = dbNodes.getAsStream(key); 
                //Does the node already has data in the index ?
                if (is == null) {
                	sameDocSet = false;
                    continue;
                }
                int lastDocId = -1;
                NodeProxy ancestor = null;
                
                while (is.available() > 0) {
                    int storedDocId = is.readInt();
                    byte ordered = is.readByte();
                    int gidsCount = is.readInt();
                    //TOUNDERSTAND -pb
                    int size = is.readFixedInt();
                    DocumentImpl storedDocument = docs.getDoc(storedDocId);
                    //Exit if the document is not concerned
                    if (storedDocument == null) {
                        is.skipBytes(size);
                        continue;
                    }
                    // position the context iterator on the next document
                    if (storedDocId != lastDocId || ordered == ENTRIES_UNORDERED) {
                    	citer.nextDocument(storedDocument);
                    	lastDocId = storedDocId;
                    	ancestor = citer.nextNode();
                    }
                    // no ancestor node in the context set, skip the document
                    if (ancestor == null || gidsCount == 0) {
                    	is.skipBytes(size);
                        continue;
                    }

                    NodeId ancestorId = ancestor.getNodeId();
                    long prevPosition = ((BFile.PageInputStream)is).position();
                    long markedPosition = prevPosition;
                    NodeId lastMarked = ancestorId;
                    NodeProxy lastAncestor = null;

                    // Process the nodes for the current document
                    NodeId nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                    long address = StorageAddress.read(is);
 
                    while (true) {
                        int relation = nodeId.computeRelation(ancestorId);
//                        System.out.println(ancestorId + " -> " + nodeId + ": " + relation);
                        if (relation != -1) {
                            // current node is a descendant. walk through the descendants
                            // and add them to the result
                            if (((axis == Constants.CHILD_AXIS || axis == Constants.ATTRIBUTE_AXIS) && relation == NodeId.IS_CHILD) || 
                            		(axis == Constants.DESCENDANT_AXIS && relation == NodeId.IS_DESCENDANT) ||
                            		axis == Constants.DESCENDANT_SELF_AXIS || axis == Constants.DESCENDANT_ATTRIBUTE_AXIS
                        		) {
                                NodeProxy storedNode = new NodeProxy(storedDocument, nodeId, nodeType, address);
                                result.add(storedNode, gidsCount);
                                if (Expression.NO_CONTEXT_ID != contextId) {
                                    storedNode.deepCopyContext(ancestor, contextId);
                                } else
                                    storedNode.copyContext(ancestor);
                            }
                            prevPosition = ((BFile.PageInputStream)is).position();
                            NodeId next = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                            if (next != DLN.END_OF_DOCUMENT) {
                                // retrieve the next descendant from the stream
                                nodeId = next;
                                address = StorageAddress.read(is);
                            } else {
                                // no more descendants. check if there are more ancestors
                                if (citer.hasNextNode()) {
                                    NodeProxy nextNode = citer.peekNode();
                                    // reached the end of the input stream:
                                    // if the ancestor set has more nodes and the following ancestor
                                    // is a descendant of the previous one, we have to rescan the input stream
                                    // for further matches
                                    if (nextNode.getNodeId().isDescendantOf(ancestorId)) {
                                        prevPosition = markedPosition;
                                        ((BFile.PageInputStream)is).seek(markedPosition);
                                        nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                                        address = StorageAddress.read(is);
                                        ancestor = citer.nextNode();
                                        ancestorId = ancestor.getNodeId();
                                    } else {
//                                        ancestorId = ancestor.getNodeId();
                                        break;
                                    }
                                } else {
                                    break;
                                }
                            }
                        } else {
                            // current node is not a descendant of the ancestor node. Compare the
                            // node ids and proceed with next descendant or ancestor.
                            int cmp = ancestorId.compareTo(nodeId);
                            if (cmp < 0) {
                                // check if we have more ancestors
                                if (citer.hasNextNode()) {
                                    NodeProxy next = citer.nextNode();
                                    // if the ancestor set has more nodes and the following ancestor
                                    // is a descendant of the previous one, we have to rescan the input stream
                                    // for further matches
                                    if (next.getNodeId().isDescendantOf(ancestorId)) {
                                        // rewind the input stream to the position from where we started
                                        // for the previous ancestor node
                                        ((BFile.PageInputStream)is).seek(markedPosition);
                                        nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                                        address = StorageAddress.read(is);
                                    } else {
                                        // mark the current position in the input stream
                                        if (!next.getNodeId().isDescendantOf(lastMarked)) {
                                            lastMarked = next.getNodeId();
                                            markedPosition = prevPosition;
                                        }
                                    }
                                    ancestor = next;
                                    ancestorId = ancestor.getNodeId();
                                } else {
                                    // no more ancestors: skip the remaining descendants for this document
                                    while (broker.getBrokerPool().getNodeFactory().createFromStream(is) 
                                            != DLN.END_OF_DOCUMENT) {
                                        StorageAddress.read(is);
                                    }
                                    break;
                                }
                            } else {
                                // load the next descendant from the input stream
                                prevPosition = ((BFile.PageInputStream)is).position();
                                NodeId nextId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                                if (nextId != DLN.END_OF_DOCUMENT) {
                                    nodeId = nextId;
                                    address = StorageAddress.read(is);
                                } else {
                                    // We need to remember the last ancestor in case there are more docs to process.
                                    // Next document should start with this ancestor.
                                    if (lastAncestor == null)
                                        lastAncestor = ancestor;
                                    
                                    // check if we have more ancestors
                                    if (citer.hasNextNode()) {
                                        ancestor = citer.nextNode();
                                        // if the ancestor set has more nodes and the following ancestor
                                        // is a descendant of the previous one, we have to rescan the input stream
                                        // for further matches
                                        if (ancestor.getNodeId().isDescendantOf(ancestorId)) {
                                            // rewind the input stream to the position from where we started
                                            // for the previous ancestor node
                                            prevPosition = markedPosition;
                                            ((BFile.PageInputStream)is).seek(markedPosition);
                                            nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);
                                            address = StorageAddress.read(is);
                                            ancestorId = ancestor.getNodeId();
                                        } else {
                                            ancestorId = ancestor.getNodeId();
                                            break;
                                        }
                                    } else {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    result.setSorted(storedDocument, ordered == ENTRIES_ORDERED);
                    if (lastAncestor != null) {
                        ancestor = lastAncestor;
                        citer.setPosition(ancestor);
                    }
                }
            } catch (EOFException e) {
                //EOFExceptions are expected here
//                LOG.warn("EOF: " + e.getMessage(), e);
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);           
                //TODO : return ?
            } finally {
                lock.release();
            }
        }
//        LOG.debug("Found: " + result.getLength() + " for " + qname);
        if (sameDocSet) {
        	result.setDocumentSet(docs);
        }
        return result;
    }
    
    private short getIndexType(byte type) {
        switch (type) {   
            case ElementValue.ATTRIBUTE_ID : //is this correct ? -pb
            case ElementValue.ATTRIBUTE :
                return Node.ATTRIBUTE_NODE;            
            case ElementValue.ELEMENT :
                return Node.ELEMENT_NODE;            
            default :
                throw new IllegalArgumentException("Invalid type");
        }
    }
    
    public Occurrences[] scanIndexedElements(Collection collection, boolean inclusive) 
            throws PermissionDeniedException {
        final User user = broker.getUser();
        if (!collection.getPermissions().validate(user, Permission.READ))
            throw new PermissionDeniedException("User '" + user.getName() + 
                    "' has no permission to read collection '" + collection.getURI() + "'");        
        List collections;
        if (inclusive) 
            collections = collection.getDescendants(broker, broker.getUser());
        else
            collections = new ArrayList();
        collections.add(collection);
        final SymbolTable symbols = broker.getSymbols();
        final TreeMap map = new TreeMap();        
        final Lock lock = dbNodes.getLock();
        for (Iterator i = collections.iterator(); i.hasNext();) {
            Collection storedCollection = (Collection) i.next();
            short storedCollectionId = storedCollection.getId();
            ElementValue startKey = new ElementValue(ElementValue.ELEMENT, storedCollectionId);
            IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, startKey);
            try {
                lock.acquire();
                //TODO : NativeValueIndex uses LongLinkedLists -pb
                ArrayList values = dbNodes.findEntries(query);
                for (Iterator j = values.iterator(); j.hasNext();) {
                    //TOUNDERSTAND : what's in there ?
                    Value val[] = (Value[]) j.next();
                    short sym = ByteConversion.byteToShort(val[0].getData(), 3);
                    short nsSymbol = ByteConversion.byteToShort(val[0].getData(), 5);
                    String name = symbols.getName(sym);
                    String namespace;
                    if (nsSymbol == 0) {
                        namespace = "";
                    } else {
                        namespace = symbols.getNamespace(nsSymbol);
                    }                    
                    QName qname = new QName(name, namespace);
                    Occurrences oc = (Occurrences) map.get(qname);
                    if (oc == null) {
                        // required for namespace lookups
                        final XQueryContext context = new XQueryContext(broker, AccessContext.INTERNAL_PREFIX_LOOKUP);                        
                        qname.setPrefix(context.getPrefixForURI(namespace));
                        oc = new Occurrences(qname);
                        map.put(qname, oc);
                    }
                    VariableByteArrayInput is = new VariableByteArrayInput(val[1].data(), val[1].start(), val[1].getLength());
                    try {
                        while (is.available() > 0) { 
                            is.readInt();
                            is.readByte();
                            int gidsCount = is.readInt();
                            //TOUNDERSTAND -pb
                            int size = is.readFixedInt();                            
                            is.skipBytes(size);
                            oc.addOccurrences(gidsCount);
                        }                    
                    } catch (EOFException e) {
                        //TODO : remove this block if unexpected -pb
                        LOG.warn("REPORT ME " + e.getMessage(), e);                    
                    }
                }
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);                
            } catch (BTreeException e) {
                LOG.error(e.getMessage(), e);
                //TODO : return ?
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
                //TODO : return ?           
            } catch (TerminatedException e) {
                LOG.warn(e.getMessage(), e);
            } finally {
                lock.release();
            }
        }
        Occurrences[] result = new Occurrences[map.size()];
        return (Occurrences[]) map.values().toArray(result);
    }   

    //TODO : note that this is *not* this.doc -pb
    public void consistencyCheck(DocumentImpl document) throws EXistException {
        final SymbolTable symbols = broker.getSymbols();
        final short collectionId = document.getCollection().getId();
        final Value ref = new ElementValue(collectionId);
        final IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        final StringBuffer msg = new StringBuffer();    
        final Lock lock = dbNodes.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            //TODO : NativeValueIndex uses LongLinkedLists -pb
            ArrayList elements = dbNodes.findKeys(query);           
            for (int i = 0; i < elements.size(); i++) {
                Value key = (Value) elements.get(i);
                Value value = dbNodes.get(key);
                short sym = ByteConversion.byteToShort(key.data(), key.start() + 3);
                String nodeName = symbols.getName(sym);
                msg.setLength(0);
                msg.append("Checking ").append(nodeName).append(": ");                
                VariableByteArrayInput is = new VariableByteArrayInput(value.getData());
                try {
                    while (is.available() > 0) {
                        int storedDocId = is.readInt();
                        is.readByte();
                        int gidsCount = is.readInt();
                        //TOUNDERSTAND -pb
                        is.readFixedInt(); //unused
                        if (storedDocId != document.getDocId()) {
                            // data are related to another document:
                            // ignore them 
                            is.skip(gidsCount * 4);
                        } else {
                            // data are related to our document:
                            // check   
                            for (int j = 0; j < gidsCount; j++) {
                            	NodeId nodeId = broker.getBrokerPool().getNodeFactory().createFromStream(is);                                
                                long address = StorageAddress.read(is);
                                Node storedNode = broker.objectWith(new NodeProxy(doc, nodeId, address));
                                if (storedNode == null) {
                                    throw new EXistException("Node " + nodeId + " in document " + document.getFileURI() + " not found.");
                                }
                                if (storedNode.getNodeType() != Node.ELEMENT_NODE && storedNode.getNodeType() != Node.ATTRIBUTE_NODE) {
                                    LOG.error("Node " + nodeId + " in document " +  document.getFileURI() + " is not an element or attribute node.");
                                    LOG.error("Type = " + storedNode.getNodeType() + "; name = " + storedNode.getNodeName() + "; value = " + storedNode.getNodeValue());
                                    throw new EXistException("Node " + nodeId + " in document " + document.getURI() + " is not an element or attribute node.");
                                }
                                if(!storedNode.getLocalName().equals(nodeName)) {
                                    LOG.error("Node name does not correspond to index entry. Expected " + nodeName + "; found " + storedNode.getLocalName());
                                    //TODO : also throw an exception here ?
                                }
                                //TODO : better message (see above) -pb
                                msg.append(StorageAddress.toString(address)).append(" ");
                            }
                        }                            
                    }                
                } catch (EOFException e) {
                    //TODO : remove this block if unexpected -pb
                    LOG.warn("REPORT ME " + e.getMessage(), e);
                }
                LOG.debug(msg.toString());
            }
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock for '" + dbNodes.getFile().getName() + "'", e);   
            //TODO : throw an exception ? -pb
        } catch (BTreeException e) {
            LOG.error(e.getMessage(), e);
            //TODO : throw an exception ? -pb
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
            //TODO : throw an exception ? -pb
        } catch (TerminatedException e) {
            LOG.warn(e.getMessage(), e);
            //TODO : throw an exception ? -pb
        } finally {
            lock.release();
        }
    } 
    
    private Value computeKey(short collectionId, QName qname) {
        return computeTypedKey(qname.getNameType(), collectionId, qname);        
    }
   
    private Value computeTypedKey(byte type, short collectionId, QName qname) {    
        if (type == ElementValue.ATTRIBUTE_ID) {
            return new ElementValue(type, collectionId, qname.getLocalName());
        } else {
            final SymbolTable symbols = broker.getSymbols();
            short sym = symbols.getSymbol(qname.getLocalName());
            //TODO : should we truncate the key ?
            //TODO : beware of the polysemy for getPrefix == null
            //if (qname.getPrefix() == null)
            //    return new ElementValue(type, collectionId, sym); 
            short nsSym = symbols.getNSSymbol(qname.getNamespaceURI());            
            return new ElementValue(type, collectionId, sym, nsSym);
        }
    }
    
    private static boolean containsNode(List list, NodeId nodeId) {
        for (int i = 0; i < list.size(); i++) {
            if (((NodeProxy) list.get(i)).getNodeId().equals(nodeId)) 
                return true;
        }
        return false;
    }    
    
    public boolean close() throws DBException {
        return dbNodes.close();
    }
    
    public void printStatistics() {
        dbNodes.printStatistics();
    }
    
    public String toString() {
        return this.getClass().getName() + " at "+ dbNodes.getFile().getName() +
        " owned by " + broker.toString();
    }

}

package com.thinkaurelius.titan.diskstorage.indexing;

import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TransactionHandle;

import java.util.List;
import java.util.Map;

/**
 * External index for querying.
 * An index can contain an arbitrary number of index stores which are updated and queried separately.
 *
 * (c) Matthias Broecheler (me@matthiasb.com)
 */

public interface IndexProvider extends IndexInformation {

    /**
     * This method registers a new key for the specified index store with the given data type. This allows the IndexProvider
     * to prepare the index if necessary.
     *
     * It is expected that this method is first called with each new key to inform the index of the expected type before the
     * key is used in any documents.
     *
     * @param store Index store
     * @param key New key to register
     * @param dataType Datatype to register for the key
     * @param tx enclosing transaction
     * @throws StorageException
     */
    public void register(String store, String key, Class<?> dataType, TransactionHandle tx) throws StorageException;

    /**
     * Mutates the index (adds and removes fields or entire documents)
     *
     * @param mutations Updates to the index. First map contains all the mutations for each store. The inner map contains
     *                  all changes for each document in an {@link IndexMutation}.
     * @param tx Enclosing transaction
     * @throws StorageException
     * @see IndexMutation
     */
    public void mutate(Map<String,Map<String, IndexMutation>> mutations, TransactionHandle tx) throws StorageException;

    /**
     * Executes the given query against the index.
     *
     * @param query Query to execute
     * @param tx Enclosing transaction
     * @return The ids of all matching documents
     * @throws StorageException
     * @see IndexQuery
     */
    public List<String> query(IndexQuery query, TransactionHandle tx) throws StorageException;

    /**
     * Returns a transaction handle for a new index transaction.
     *
     * @return New Transaction Handle
     */
    public TransactionHandle beginTransaction() throws StorageException;

    /**
     * Closes the index
     * @throws StorageException
     */
    public void close() throws StorageException;

    /**
     * Clears the index and removes all entries in all stores.
     * @throws StorageException
     */
    public void clearStorage() throws StorageException;

}

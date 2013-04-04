package com.thinkaurelius.titan.diskstorage.cassandra.thrift;

import static com.thinkaurelius.titan.diskstorage.cassandra.CassandraTransaction.getTx;
import static org.apache.cassandra.db.Table.SYSTEM_KS;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.dht.ByteOrderedPartitioner;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.RandomPartitioner;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.Deletion;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KsDef;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SchemaDisagreementException;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.apache.thrift.TException;
import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.thinkaurelius.titan.diskstorage.Backend;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;
import com.thinkaurelius.titan.diskstorage.cassandra.AbstractCassandraStoreManager;
import com.thinkaurelius.titan.diskstorage.cassandra.thrift.thriftpool.CTConnection;
import com.thinkaurelius.titan.diskstorage.cassandra.thrift.thriftpool.CTConnectionFactory;
import com.thinkaurelius.titan.diskstorage.cassandra.thrift.thriftpool.CTConnectionFactory.Config;
import com.thinkaurelius.titan.diskstorage.cassandra.thrift.thriftpool.CTConnectionPool;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.Entry;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KCVMutation;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreTransaction;
import com.thinkaurelius.titan.diskstorage.util.ByteBufferUtil;
import com.thinkaurelius.titan.diskstorage.util.Hex;
import com.thinkaurelius.titan.diskstorage.util.TimeUtility;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.util.system.IOUtils;

/**
 * This class creates {@see CassandraThriftKeyColumnValueStore}s and handles
 * Cassandra-backed allocation of vertex IDs for Titan (when so configured).
 * 
 * @author Dan LaRocque <dalaro@hopcount.org>
 * @author Matthias Broecheler <me@matthiasb.com>
 */
public class CassandraThriftStoreManager extends AbstractCassandraStoreManager {
	private static final Logger log = LoggerFactory
			.getLogger(CassandraThriftStoreManager.class);

	private final Map<String, CassandraThriftKeyColumnValueStore> openStores;

	private final CTConnectionPool pool;
	private final CTConnectionFactory factory;

	public CassandraThriftStoreManager(Configuration config)
			throws StorageException {
		super(config);

		String randomInitialHostname = getSingleHostname();

		int thriftTimeoutMS = config.getInt(
				GraphDatabaseConfiguration.CONNECTION_TIMEOUT_KEY,
				GraphDatabaseConfiguration.CONNECTION_TIMEOUT_DEFAULT);

		int maxTotalConnections = config.getInt(
				GraphDatabaseConfiguration.CONNECTION_POOL_SIZE_KEY,
				GraphDatabaseConfiguration.CONNECTION_POOL_SIZE_DEFAULT);

		this.factory = new CTConnectionFactory(randomInitialHostname, port,
				thriftTimeoutMS, thriftFrameSize, thriftMaxMessageSize);

		CTConnectionPool p = new CTConnectionPool(factory);
		p.setTestOnBorrow(true);
		p.setTestOnReturn(true);
		p.setTestWhileIdle(false);
		p.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_GROW);
		p.setMaxActive(-1); // "A negative value indicates no limit"
		p.setMaxTotal(maxTotalConnections); // maxTotal limits active + idle

		this.pool = p;

		this.openStores = new HashMap<String, CassandraThriftKeyColumnValueStore>();

		// Only watch the ring and change endpoints with BOP
		if (getCassandraPartitioner() instanceof ByteOrderedPartitioner) {
			this.backgroundThread = new Thread(new HostUpdater());
			this.backgroundThread.start();
		}
	}

	@Override
	public Partitioner getPartitioner() throws StorageException {
		return Partitioner.getPartitioner(getCassandraPartitioner());
	}

	public IPartitioner<?> getCassandraPartitioner() throws StorageException {
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(SYSTEM_KS);
			return (IPartitioner<?>) Class.forName(
					conn.getClient().describe_partitioner()).newInstance();
		} catch (Exception e) {
			throw new TemporaryStorageException(e);
		} finally {
			IOUtils.closeQuietly(conn);
		}
	}

	@Override
	public String toString() {
		return "thriftCassandra" + super.toString();
	}

	@Override
	public void close() throws StorageException {
		openStores.clear();
		closePool();
	}

	@Override
	public void mutateMany(Map<String, Map<ByteBuffer, KCVMutation>> mutations,
			StoreTransaction txh) throws StorageException {
		Preconditions.checkNotNull(mutations);

		long deletionTimestamp = TimeUtility.getApproxNSSinceEpoch(false);
		long additionTimestamp = TimeUtility.getApproxNSSinceEpoch(true);

		ConsistencyLevel consistency = getTx(txh).getWriteConsistencyLevel()
				.getThriftConsistency();

		// Generate Thrift-compatible batch_mutate() datastructure
		// key -> cf -> cassmutation
		int size = 0;
		for (Map<ByteBuffer, KCVMutation> mutation : mutations.values())
			size += mutation.size();
		Map<ByteBuffer, Map<String, List<org.apache.cassandra.thrift.Mutation>>> batch = new HashMap<ByteBuffer, Map<String, List<org.apache.cassandra.thrift.Mutation>>>(
				size);

		ByteBuffer firstKey = null;

		for (Map.Entry<String, Map<ByteBuffer, KCVMutation>> keyMutation : mutations
				.entrySet()) {
			String columnFamily = keyMutation.getKey();
			for (Map.Entry<ByteBuffer, KCVMutation> mutEntry : keyMutation
					.getValue().entrySet()) {
				ByteBuffer key = mutEntry.getKey();

				if (null == firstKey) {
					firstKey = key;
				}

				Map<String, List<org.apache.cassandra.thrift.Mutation>> cfmutation = batch
						.get(key);
				if (cfmutation == null) {
					cfmutation = new HashMap<String, List<org.apache.cassandra.thrift.Mutation>>(
							3);
					batch.put(key, cfmutation);
				}

				KCVMutation mutation = mutEntry.getValue();
				List<org.apache.cassandra.thrift.Mutation> thriftMutation = new ArrayList<org.apache.cassandra.thrift.Mutation>(
						mutations.size());

				if (mutation.hasDeletions()) {
					for (ByteBuffer buf : mutation.getDeletions()) {
						Deletion d = new Deletion();
						SlicePredicate sp = new SlicePredicate();
						sp.addToColumn_names(buf);
						d.setPredicate(sp);
						d.setTimestamp(deletionTimestamp);
						org.apache.cassandra.thrift.Mutation m = new org.apache.cassandra.thrift.Mutation();
						m.setDeletion(d);
						thriftMutation.add(m);
					}
				}

				if (mutation.hasAdditions()) {
					for (Entry ent : mutation.getAdditions()) {
						ColumnOrSuperColumn cosc = new ColumnOrSuperColumn();
						Column column = new Column(ent.getColumn());
						column.setValue(ent.getValue());
						column.setTimestamp(additionTimestamp);
						cosc.setColumn(column);
						org.apache.cassandra.thrift.Mutation m = new org.apache.cassandra.thrift.Mutation();
						m.setColumn_or_supercolumn(cosc);
						thriftMutation.add(m);
					}
				}

				cfmutation.put(columnFamily, thriftMutation);
			}
		}

		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keySpaceName);
			Cassandra.Client client = conn.getClient();

			client.batch_mutate(batch, consistency);
			if (null != firstKey)
				updateCounter(firstKey);
		} catch (Exception ex) {
			throw CassandraThriftKeyColumnValueStore.convertException(ex);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keySpaceName, conn);
		}

	}

	public ByteBuffer getKeyEndToken(ByteBuffer key) {
		
		ImmutableMap<ByteBuffer, String> tokenMap = nodesByEndToken;
		
		return getKeyEndToken(key, tokenMap);
	}
	
	private ByteBuffer getKeyEndToken(ByteBuffer key, ImmutableMap<ByteBuffer, String> tokenMap) {
		
		if (0 == tokenMap.size()) {
			return null;
		}
		
		ByteBuffer lastToken = null;
		ByteBuffer result = null;
		
		for (ByteBuffer curToken : tokenMap.keySet()) {
			if (null == lastToken) { // Special case for first iteration
				if (ByteBufferUtil.isSmallerOrEqualThan(key, curToken)) {
					// This key is part of the "wrapping range"
					result = curToken;
					
					if (log.isTraceEnabled()) {
						log.trace("Key {} precedes or equals first token {}",
								ByteBufferUtil.bytesToHex(key),
								ByteBufferUtil.bytesToHex(curToken));
					}
					
					break;
				}
			} else if (ByteBufferUtil.isSmallerOrEqualThan(key, curToken) &&
				ByteBufferUtil.isSmallerThan(lastToken, key)) { // General case
				result = curToken;
				
				if (log.isTraceEnabled()) {
					log.trace("Key {} falls between tokens {} and {}", new Object[] {
							ByteBufferUtil.bytesToHex(key),
							ByteBufferUtil.bytesToHex(lastToken),
							ByteBufferUtil.bytesToHex(curToken)
					});
				}
				
				break;
			}
			
			lastToken = curToken;
		}
		
		// Wrapping range case 2: key is greater than all tokens
		if (result == null) {
			assert 0 < tokenMap.size();
			assert null != lastToken;
			
			result = tokenMap.keySet().iterator().next();
			
			assert null != result;

			if (log.isTraceEnabled()) {
				log.trace("Key {} succeeds all tokens (last token was {}); "
					+ "assigning it to initial token {}",
					new Object[] {
							ByteBufferUtil.bytesToHex(key),
							ByteBufferUtil.bytesToHex(lastToken),
							ByteBufferUtil.bytesToHex(result)
					}
				);
			}
		}
		
		return result;
	}

	public String getKeyHostname(ByteBuffer key) {
		
		ImmutableMap<ByteBuffer, String> tokenMap = nodesByEndToken;
		
		ByteBuffer bb = getKeyEndToken(key, tokenMap);
		
		return null == bb ? null : tokenMap.get(bb);
	}
	
	public void updateCounter(ByteBuffer key) {
		ByteBuffer token = getKeyEndToken(key);
		
		if (null == token) {
			if (log.isDebugEnabled()) {
				log.debug("No token found for key {} (skipping counter update)", 
						ByteBufferUtil.bytesToHex(key));
			}
			return;
		}
		
        Counter c = countsByEndToken.get(token);
        if (null == c) {
            countsByEndToken.putIfAbsent(token, new Counter());
            c = countsByEndToken.get(token);
        }
        assert null != c;
        
        c.update();
        
        if (log.isTraceEnabled()) {
        	log.trace("Updated counter for token {} (responsible for key {})",
        			ByteBufferUtil.bytesToHex(token), ByteBufferUtil.bytesToHex(key));
        }
	}

	@Override
	// TODO: *BIG FAT WARNING* 'synchronized is always *bad*, change openStores
	// to use ConcurrentLinkedHashMap
	public synchronized CassandraThriftKeyColumnValueStore openDatabase(
			final String name) throws StorageException {
		
		if (openStores.containsKey(name))
			return openStores.get(name);

		ensureColumnFamilyExists(keySpaceName, name);

		CassandraThriftKeyColumnValueStore store = new CassandraThriftKeyColumnValueStore(
				keySpaceName, name, this);
		
		openStores.put(name, store);
		
		return store;
	}

	/**
	 * Connect to Cassandra via Thrift on the specified host and port and
	 * attempt to truncate the named keyspace.
	 * 
	 * This is a utility method intended mainly for testing. It is equivalent to
	 * issuing 'truncate <cf>' for each of the column families in keyspace using
	 * the cassandra-cli tool.
	 * 
	 * Using truncate is better for a number of reasons, most significantly
	 * because it doesn't involve any schema modifications which can take time
	 * to propagate across the cluster such leaves nodes in the inconsistent
	 * state and could result in read/write failures. Any schema modifications
	 * are discouraged until there is no traffic to Keyspace or ColumnFamilies.
	 * 
	 * @throws StorageException
	 *             if any checked Thrift or UnknownHostException is thrown in
	 *             the body of this method
	 */
	public void clearStorage() throws StorageException {
		openStores.clear();

		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(SYSTEM_KS);
			Cassandra.Client client = conn.getClient();

			try {
				client.set_keyspace(keySpaceName);

				KsDef ksDef = client.describe_keyspace(keySpaceName);

				for (CfDef cfDef : ksDef.getCf_defs())
					client.truncate(cfDef.name);

				// Clearing the pool is unnecessary now that we no longer drop
				// keyspaces
				// pool.clear(keySpaceName);
			} catch (InvalidRequestException e) { // Keyspace doesn't exist yet:
													// return immediately
				log.debug(
						"Keyspace {} does not exist, not attempting to truncate.",
						keySpaceName);
			}
		} catch (Exception e) {
			throw new TemporaryStorageException(e);
		} finally {
			if (conn != null)
				pool.genericReturnObject(SYSTEM_KS, conn);
		}
	}
	
	CTConnectionPool getPool() {
		return pool;
	}

	private KsDef ensureKeyspaceExists(String keyspaceName)
			throws NotFoundException, InvalidRequestException, TException,
			SchemaDisagreementException, StorageException {

		CTConnection connection = pool.genericBorrowObject(SYSTEM_KS);

		Preconditions.checkNotNull(connection);

		try {
			Cassandra.Client client = connection.getClient();

			try {
				client.set_keyspace(keyspaceName);
				client.set_keyspace(SYSTEM_KS);
				log.debug("Found existing keyspace {}", keyspaceName);
			} catch (InvalidRequestException e) {
				// Keyspace didn't exist; create it
				log.debug("Creating keyspace {}...", keyspaceName);

				KsDef ksdef = new KsDef()
						.setName(keyspaceName)
						.setCf_defs(new LinkedList<CfDef>())
						// cannot be null but can be empty
						.setStrategy_class(
								"org.apache.cassandra.locator.SimpleStrategy")
						.setStrategy_options(
								new ImmutableMap.Builder<String, String>().put(
										"replication_factor",
										String.valueOf(replicationFactor))
										.build());

				String schemaVer = client.system_add_keyspace(ksdef);

				// Try to block until Cassandra converges on the new keyspace
				try {
					CTConnectionFactory.validateSchemaIsSettled(client,
							schemaVer);
				} catch (InterruptedException ie) {
					throw new TemporaryStorageException(ie);
				}
			}

			return client.describe_keyspace(keyspaceName);

		} finally {
			IOUtils.closeQuietly(connection);
		}
	}

	private void ensureColumnFamilyExists(String ksName, String cfName)
			throws StorageException {
		ensureColumnFamilyExists(ksName, cfName,
				"org.apache.cassandra.db.marshal.BytesType");
	}

	private void ensureColumnFamilyExists(String ksName, String cfName,
			String comparator) throws StorageException {
		CTConnection conn = null;
		try {
			KsDef keyspaceDef = ensureKeyspaceExists(ksName);

			conn = pool.genericBorrowObject(ksName);
			Cassandra.Client client = conn.getClient();

			log.debug("Looking up metadata on keyspace {}...", ksName);

			boolean foundColumnFamily = false;
			for (CfDef cfDef : keyspaceDef.getCf_defs()) {
				String curCfName = cfDef.getName();
				if (curCfName.equals(cfName))
					foundColumnFamily = true;
			}

			if (!foundColumnFamily) {
				createColumnFamily(client, ksName, cfName, comparator);
			} else {
				log.debug("Keyspace {} and ColumnFamily {} were found.",
						ksName, cfName);
			}
		} catch (SchemaDisagreementException e) {
			throw new TemporaryStorageException(e);
		} catch (Exception e) {
			throw new PermanentStorageException(e);
		} finally {
			IOUtils.closeQuietly(conn);
		}
	}

	private static void createColumnFamily(Cassandra.Client client,
			String ksName, String cfName, String comparator)
			throws StorageException {
		CfDef createColumnFamily = new CfDef();
		createColumnFamily.setName(cfName);
		createColumnFamily.setKeyspace(ksName);
		createColumnFamily.setComparator_type(comparator);
		createColumnFamily
				.setCompression_options(new ImmutableMap.Builder<String, String>()
						.put("sstable_compression", "SnappyCompressor")
						.put("chunk_length_kb", "64").build());

		// Hard-coded caching settings
		if (cfName.startsWith(Backend.EDGESTORE_NAME)) {
			createColumnFamily.setCaching("keys_only");
		} else if (cfName.startsWith(Backend.VERTEXINDEX_STORE_NAME)) {
			createColumnFamily.setCaching("rows_only");
		}

		log.debug("Adding column family {} to keyspace {}...", cfName, ksName);
		String schemaVer;
		try {
			schemaVer = client.system_add_column_family(createColumnFamily);
		} catch (SchemaDisagreementException e) {
			throw new TemporaryStorageException(
					"Error in setting up column family", e);
		} catch (Exception e) {
			throw new PermanentStorageException(e);
		}

		log.debug("Added column family {} to keyspace {}.", cfName, ksName);

		// Try to let Cassandra converge on the new column family
		try {
			CTConnectionFactory.validateSchemaIsSettled(client, schemaVer);
		} catch (InterruptedException e) {
			throw new TemporaryStorageException(e);
		}

	}

	@Override
	public String getConfigurationProperty(final String key)
			throws StorageException {
		CTConnection connection = null;

		try {
			ensureColumnFamilyExists(keySpaceName, SYSTEM_PROPERTIES_CF,
					"org.apache.cassandra.db.marshal.UTF8Type");

			connection = pool.genericBorrowObject(SYSTEM_KS);
			Cassandra.Client client = connection.getClient();

			client.set_keyspace(keySpaceName);

			ColumnOrSuperColumn column = client.get(UTF8Type.instance
					.fromString(SYSTEM_PROPERTIES_KEY), new ColumnPath(
					SYSTEM_PROPERTIES_CF).setColumn(UTF8Type.instance
					.fromString(key)), ConsistencyLevel.QUORUM);

			if (column == null || !column.isSetColumn())
				return null;

			Column actualColumn = column.getColumn();

			return (actualColumn.value == null) ? null : UTF8Type.instance
					.getString(actualColumn.value);
		} catch (NotFoundException e) {
			return null;
		} catch (Exception e) {
			throw new PermanentStorageException(e);
		} finally {
			if (null != connection)
				pool.genericReturnObject(SYSTEM_KS, connection);
		}
	}

	@Override
	public void setConfigurationProperty(final String rawKey,
			final String rawValue) throws StorageException {
		CTConnection connection = null;

		try {
			ensureColumnFamilyExists(keySpaceName, SYSTEM_PROPERTIES_CF,
					"org.apache.cassandra.db.marshal.UTF8Type");

			ByteBuffer key = UTF8Type.instance.fromString(rawKey);
			ByteBuffer val = UTF8Type.instance.fromString(rawValue);

			connection = pool.genericBorrowObject(SYSTEM_KS);
			Cassandra.Client client = connection.getClient();

			client.set_keyspace(keySpaceName);

			client.insert(
					UTF8Type.instance.fromString(SYSTEM_PROPERTIES_KEY),
					new ColumnParent(SYSTEM_PROPERTIES_CF),
					new Column(key).setValue(val).setTimestamp(
							System.currentTimeMillis()),
					ConsistencyLevel.QUORUM);
		} catch (Exception e) {
			throw new PermanentStorageException(e);
		} finally {
			if (null != connection)
				pool.genericReturnObject(SYSTEM_KS, connection);
		}
	}

	private static final double DECAY_EXPONENT_MULTI = 0.0005;
	private static final int BG_THREAD_WAIT_TIME = 200;
	private static final int MAX_CLOSE_ATTEMPTS = 5;
	private static final int DEFAULT_UPDATE_INTERVAL = 4000
			+ BG_THREAD_WAIT_TIME * MAX_CLOSE_ATTEMPTS;

	private Thread backgroundThread = null;

	private final NonBlockingHashMap<ByteBuffer, Counter> countsByEndToken = new NonBlockingHashMap<ByteBuffer, Counter>();
	private volatile ImmutableMap<ByteBuffer, String> nodesByEndToken = ImmutableMap.of();

	private void updatePools() throws InterruptedException {
	
		ByteBuffer hottestEndToken = null;
		double hottestEndTokenValue = 0D;
		
		/*
		 * Count the number of iterations over countsByEndToken.entrySet()'s
		 * members and store the result in perceivedEntrySetSize. We do this
		 * instead of just calling countsByEntrySet.size() later because the map
		 * contents and size could change between the invocation of entrySet()
		 * and the later line of code where we want to know how large the entry
		 * set was.
		 */
		int perceivedEntrySetSize = 0;
		
		for (Map.Entry<ByteBuffer, Counter> entry : countsByEndToken.entrySet()) {
			if (hottestEndToken == null
					|| hottestEndTokenValue < entry.getValue().currentValue()) {
				hottestEndToken = entry.getKey();
				hottestEndTokenValue = entry.getValue().currentValue();
			}
			perceivedEntrySetSize++;
		}

		// Talk directly to the first replica responsible for the hot token
		if (null != hottestEndToken) {

			String hotHost = getKeyHostname(hottestEndToken);
			assert null != hotHost;

			assert null != factory;
			Config cfg = factory.getConfig();
			assert null != cfg;

			String curHost = cfg.getHostname();
			assert null != curHost;

			if (curHost.equals(hotHost)) {
				log.info(
						"Already connected to hottest Cassandra endpoint: {} with hotness {}",
						hotHost, hottestEndTokenValue);
			} else {
				log.info(
						"New hottest Cassandra endpoint found: {} with hotness {}",
						hotHost, hottestEndTokenValue);
				Config newConfig = new Config(hotHost, cfg.getPort(),
						cfg.getTimeoutMS(), cfg.getFrameSize(),
						cfg.getMaxMessageSize());
				factory.setConfig(newConfig);

				assert !newConfig.equals(cfg);

				closePool();
				log.info("Directing all future Cassandra ops to {}", hotHost);
			}
		} else {
			log.debug("No hottest end token found.  countsByEndToken size={}",
					perceivedEntrySetSize);
		}
	}

	private void closePool() {
		/*
		 * pool.close() does not affect borrowed connections.
		 * 
		 * Connections currently borrowed by some thread which are
		 * talking to the old host will eventually be destroyed by
		 * CTConnectionFactory#validateObject() returning false when
		 * those connections are returned to the pool.
		 */
		try {
			pool.close();
			log.info("Closed Thrift connection pooler.");
		} catch (Exception e) {
			log.warn("Failed to close connection pooler.  "
					+ "We might be leaking Cassandra connections.", e);
			// There's still hope: CTConnectionFactory#validateObject()
			// will be called on borrow() and might tear down the
			// connections that close() failed to tear down
		}
	}
	
	/**
	 * Refresh instance variables about the Cassandra ring so that
	 * #getKeyHostname and #getKeyEndToken return up-to-date information.
	 */
	private void updateRing() {
		
		CTConnection conn = null;
		
		try {
			conn = pool.genericBorrowObject(SYSTEM_KS);
			
			Map<String, String> tm = conn.getClient().describe_token_map();
			
			ImmutableMap.Builder<ByteBuffer, String> builder =
					ImmutableMap.builder();
			
			for (Map.Entry<String, String> ent : tm.entrySet()) {
				String token = ent.getKey();
				String nodeIP = ent.getKey();
				
				ByteBuffer tokenBB = ByteBuffer.wrap(Hex.hexToBytes(token));
				
				builder.put(tokenBB, nodeIP);
			}
			
			// Publish fully-formed ring map to its volatile reference
			nodesByEndToken = builder.build();
			
			if (log.isDebugEnabled()) {
				log.debug("Updated Cassandra TokenMap:");
				for (Map.Entry<ByteBuffer, String> ent : nodesByEndToken.entrySet()) {
					log.debug("TokenMap entry: {} -> {}",
							ByteBufferUtil.bytesToHex(ent.getKey()),
							ent.getValue());
				}
				log.debug("End of Cassandra TokenMap.");
			}
		} catch (StorageException e) {
			log.error("Failed to acquire pooled Cassandra connection", e);
			// Don't propagate exception
		} catch (InvalidRequestException e) {
			log.error("Failed to describe Cassandra token map", e);
			// Don't propagate exception
		} catch (TException e) {
			log.error("Thrift Exception while getting Cassandra token map", e);
			// Don't propagate exception
		} finally {
            if (conn != null) {
            	try {
            		pool.genericReturnObject(SYSTEM_KS, conn);
            	} catch (StorageException e) {
            		log.warn("Failed to return Cassandra connection to pool." +
            				"  We might be leaking connections.", e);
            	}
            }
		}
	}

	private class HostUpdater implements Runnable {

		private long lastUpdateTime;
		private final long updateInterval;

		public HostUpdater() {
			this(DEFAULT_UPDATE_INTERVAL);
		}

		public HostUpdater(final long updateInterval) {
			Preconditions.checkArgument(updateInterval > 0);
			this.updateInterval = updateInterval;
			lastUpdateTime = System.currentTimeMillis();
		}

		@Override
		public void run() {
			while (true) {
				long sleepTime = updateInterval
						- (System.currentTimeMillis() - lastUpdateTime);
				try {
					Thread.sleep(Math.max(0, sleepTime));
					updateRing();
					updatePools();
					lastUpdateTime = System.currentTimeMillis();
					log.debug("HostUpdater lastUpdateTime={}", lastUpdateTime);
				} catch (InterruptedException e) {
					log.info("Background update thread shutting down...");
					return;
				}
			}
		}
	}

	private static class Counter {

		private double value = 0.0;
		private long lastUpdate = 0;

		public synchronized void update() {
			value = currentValue() + 1.0;
			lastUpdate = System.currentTimeMillis();
		}

		public synchronized double currentValue() {
			return value
					* Math.exp(-DECAY_EXPONENT_MULTI
							* (System.currentTimeMillis() - lastUpdate));
		}
	}

}

/*
 * Copyright (C) 2005-2014 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.solr.tracker;

import com.google.common.collect.Lists;
import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.repo.index.shard.ShardState;
import org.alfresco.solr.BoundedDeque;
import org.alfresco.solr.InformationServer;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.client.GetNodesParameters;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.client.Transactions;
import org.alfresco.util.Pair;
import org.apache.commons.codec.EncoderException;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import static org.alfresco.repo.index.shard.ShardMethodEnum.DB_ID_RANGE;

/*
 * This tracks two things: transactions and metadata nodes
 * @author Ahmed Owian
 */
public class MetadataTracker extends CoreStatePublisher implements Tracker
{
    protected final static Logger LOGGER = LoggerFactory.getLogger(MetadataTracker.class);

    private static final int DEFAULT_METADATA_TRACKER_MAX_PARALLELISM = 32;
    private static final int DEFAULT_TRANSACTION_DOCS_BATCH_SIZE = 2000;
    private static final int DEFAULT_MAX_NUMBER_OF_TRANSACTIONS = 2000;
    private static final int DEFAULT_NODE_BATCH_SIZE = 50;
    private static final String DEFAULT_INITIAL_TRANSACTION_RANGE = "0-2000";

    private int matadataTrackerParallelism;
    private int transactionDocsBatchSize;
    private int nodeBatchSize;
    private int maxNumberOfTransactions;

    private ConcurrentLinkedQueue<Long> transactionsToReindex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> transactionsToIndex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> transactionsToPurge = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> nodesToReindex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> nodesToIndex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> nodesToPurge = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<String> queriesToReindex = new ConcurrentLinkedQueue<>();
    private ForkJoinPool forkJoinPool;

    // Share run and write locks across all MetadataTracker threads
    private static Map<String, Semaphore> RUN_LOCK_BY_CORE = new ConcurrentHashMap<>();
    private static Map<String, Semaphore> WRITE_LOCK_BY_CORE = new ConcurrentHashMap<>();
    @Override
    public Semaphore getWriteLock()
    {
        return WRITE_LOCK_BY_CORE.get(coreName);
    }
    @Override
    public Semaphore getRunLock()
    {
        return RUN_LOCK_BY_CORE.get(coreName);
    }
    
    /**
     * Check if nextTxCommitTimeService is available in the repository.
     * This service is used to find the next available transaction commit time from a given time,
     * so periods of time where no document updating is happening can be skipped while getting 
     * pending transactions list.
     * 
     * {@link org.alfresco.solr.client.SOLRAPIClient#GET_NEXT_TX_COMMIT_TIME}
     */
    private boolean nextTxCommitTimeServiceAvailable = false;
    
    /**
     * Check if txInteravlCommitTimeService is available in the repository.
     * This service returns the minimum and the maximum commit time for transactions in a node id range,
     * so method sharding DB_ID_RANGE can skip transactions not relevant for the DB ID range. 
     * 
     * {@link org.alfresco.solr.client.SOLRAPIClient#GET_TX_INTERVAL_COMMIT_TIME}
     */
    private boolean txIntervalCommitTimeServiceAvailable = false;
    /** Whether the cascade tracking is enabled. */
    private boolean cascadeTrackerEnabled = true;
    
    /**
     * Transaction Id range to get the first transaction in database. 
     * 0-2000 by default.
     */
    private Pair<Long, Long> minTxnIdRange;

    public MetadataTracker(final boolean isMaster, Properties p, SOLRAPIClient client, String coreName,
            InformationServer informationServer)
    {
        this(isMaster, p, client, coreName, informationServer, false);
    }

    /**
     * MetadataTracker constructor
     * 
     * @param isMaster is true if SOLR instance is master, false otherwise
     * @param p includes SOLR core properties (from environment variables and properties file)
     * @param client Alfresco Repository http client
     * @param coreName Name of the SOLR Core (alfresco, archive)
     * @param informationServer SOLR Information Server
     * @param checkRepoServicesAvailability is true if Repo Services availability needs to be checked
     */
    public MetadataTracker(final boolean isMaster, Properties p, SOLRAPIClient client, String coreName,
                InformationServer informationServer, boolean checkRepoServicesAvailability)
    {
        super(isMaster, p, client, coreName, informationServer, Tracker.Type.METADATA);

        transactionDocsBatchSize = Integer.parseInt(p.getProperty("alfresco.transactionDocsBatchSize",
                String.valueOf(DEFAULT_TRANSACTION_DOCS_BATCH_SIZE)));
        nodeBatchSize = Integer.parseInt(p.getProperty("alfresco.nodeBatchSize",
                String.valueOf(DEFAULT_NODE_BATCH_SIZE)));
        maxNumberOfTransactions = Integer.parseInt(p.getProperty("alfresco.maxNumberOfTransactions", String.valueOf(DEFAULT_MAX_NUMBER_OF_TRANSACTIONS)));
        matadataTrackerParallelism = Integer.parseInt(p.getProperty("alfresco.metadataTrackerMaxParallelism",
                String.valueOf(DEFAULT_METADATA_TRACKER_MAX_PARALLELISM)));

        String[] minTxninitialRangeString = p.getProperty("solr.initial.transaction.range", DEFAULT_INITIAL_TRANSACTION_RANGE).split("-");
        cascadeTrackerEnabled = informationServer.cascadeTrackingEnabled();
        minTxnIdRange = new Pair<>(Long.valueOf(minTxninitialRangeString[0]), Long.valueOf(minTxninitialRangeString[1]));
        forkJoinPool = new ForkJoinPool(matadataTrackerParallelism);

        RUN_LOCK_BY_CORE.put(coreName, new Semaphore(1, true));
        WRITE_LOCK_BY_CORE.put(coreName, new Semaphore(1, true));
        
        // In order to apply performance optimizations, checking the availability of Repo Web Scripts is required.
        // As these services are available from ACS 6.2
        if (checkRepoServicesAvailability)
        {
            // Try invoking getNextTxCommitTime service
            try
            {
                client.getNextTxCommitTime(coreName, 0l);
                nextTxCommitTimeServiceAvailable = true;
            }
            catch (NoSuchMethodException e)
            {
                LOGGER.warn("nextTxCommitTimeService is not available. Upgrade your ACS Repository version in order to use this feature: {} ", e.getMessage());
            }
            catch (Exception e)
            {
                LOGGER.error("Checking nextTxCommitTimeService failed.", e);
            }
    
            // Try invoking txIntervalCommitTime service
            if (shardMethod.equals(DB_ID_RANGE))
            {
                try
                {
                    client.getTxIntervalCommitTime(coreName, 0l, 0l);
                    txIntervalCommitTimeServiceAvailable = true;
                }
                catch (NoSuchMethodException e)
                {
                    LOGGER.warn("txIntervalCommitTimeServiceAvailable is not available. Upgrade your ACS Repository version " +
                            "to use this feature with DB_ID_RANGE sharding: {} ", e.getMessage());
                }
                catch (Exception e)
                {
                    LOGGER.error("Checking txIntervalCommitTimeServiceAvailable failed.", e);
                }
            }
        }
    
    }

    MetadataTracker()
    {
        super(Tracker.Type.METADATA);
    }

    @Override
    protected void doTrack(String iterationId) throws AuthenticationException, IOException, JSONException, EncoderException
    {
        // MetadataTracker must wait until ModelTracker has run
        ModelTracker modelTracker = this.infoSrv.getAdminHandler().getTrackerRegistry().getModelTracker();
        if (modelTracker != null && modelTracker.hasModels())
        {
            trackRepository();
        }
        else
        {
            invalidateState();
        }
    }

    public void maintenance() throws Exception
    {
        purgeTransactions();
        purgeNodes();
        reindexTransactions();
        reindexNodes();
        reindexNodesByQuery();
        indexTransactions();
        indexNodes();
    }

    public boolean hasMaintenance()
    {
        return  transactionsToReindex.size() > 0 ||
                transactionsToIndex.size() > 0 ||
                transactionsToPurge.size() > 0 ||
                nodesToReindex.size() > 0 ||
                nodesToIndex.size() > 0 ||
                nodesToPurge.size() > 0 ||
                queriesToReindex.size() > 0;
    }

    private void trackRepository() throws IOException, AuthenticationException, JSONException, EncoderException
    {
        checkShutdown();


        // Check we are tracking the correct repository
        TrackerState state = super.getTrackerState();
        if(state.getTrackerCycles() == 0)
        {
            //We have a new tracker state so do the checks.
            checkRepoAndIndexConsistency(state);
        }

        if(docRouter instanceof DBIDRangeRouter)
        {
            DBIDRangeRouter dbidRangeRouter = (DBIDRangeRouter)docRouter;
            long indexCap = infoSrv.getIndexCap();
            long endRange = dbidRangeRouter.getEndRange();
            assert(indexCap == -1 || indexCap >= endRange);

            if(indexCap > endRange) {
                dbidRangeRouter.setExpanded(true);
                dbidRangeRouter.setEndRange(indexCap);
            }

            dbidRangeRouter.setInitialized(true);
        }

        checkShutdown();
        trackTransactions();
    }

    /**
     * Checks the first and last TX time
     * @param state the state of this tracker
     * @throws AuthenticationException
     * @throws IOException
     * @throws JSONException
     */
    private void checkRepoAndIndexConsistency(TrackerState state) throws AuthenticationException, IOException, JSONException
    {
        Transactions firstTransactions = null;
        if (state.getLastGoodTxCommitTimeInIndex() == 0) 
        {
            state.setCheckedLastTransactionTime(true);
            state.setCheckedFirstTransactionTime(true);
            LOGGER.info("No transactions found - no verification required");

            firstTransactions = client.getTransactions(null, minTxnIdRange.getFirst(), null, minTxnIdRange.getSecond(), 1);
            if (!firstTransactions.getTransactions().isEmpty())
            {
                Transaction firstTransaction = firstTransactions.getTransactions().get(0);
                long firstTransactionCommitTime = firstTransaction.getCommitTimeMs();
                state.setLastGoodTxCommitTimeInIndex(firstTransactionCommitTime);
                setLastTxCommitTimeAndTxIdInTrackerState(firstTransactions);
            }
        }
        
        if (!state.isCheckedFirstTransactionTime())
        {
            
            // On Shards configured with DB_ID_RANGE, the first indexed transaction can be
            // different from the first transaction in the repository as some transactions
            // are skipped if they are not related with the range of the Shard.
            // Getting the minCommitTime for the Shard is enough in order to check
            // that the first transaction is present.
            long minCommitTime = 0l;
            if (docRouter instanceof DBIDRangeRouter && txIntervalCommitTimeServiceAvailable)
            {
                try
                {
                    DBIDRangeRouter dbIdRangeRouter = (DBIDRangeRouter) docRouter;
                    Pair<Long, Long> commitTimes = client.getTxIntervalCommitTime(coreName,
                            dbIdRangeRouter.getStartRange(), dbIdRangeRouter.getEndRange());
                    minCommitTime = commitTimes.getFirst();
                }
                catch (NoSuchMethodException e)
                {
                    LOGGER.warn("txIntervalCommitTimeServiceAvailable is not available. If you are using DB_ID_RANGE shard method, "
                            + "upgrade your ACS Repository version in order to use the skip transactions feature: {} ", e.getMessage());
                }
            }
            
            // When a Shard with DB_ID_RANGE method is empty, minCommitTime is -1.
            // No firstTransaction checking is required for this case.
            if (minCommitTime != -1l) {
            
                firstTransactions = client.getTransactions(minCommitTime, 0L, null, 2000l, 1);
                if (!firstTransactions.getTransactions().isEmpty())
                {
                    Transaction firstTransaction = firstTransactions.getTransactions().get(0);
                    long firstTxId = firstTransaction.getId();
                    long firstTransactionCommitTime = firstTransaction.getCommitTimeMs();
                    int setSize = this.infoSrv.getTxDocsSize(""+firstTxId, ""+firstTransactionCommitTime);
                    
                    if (setSize == 0)
                    {
                        LOGGER.error("First transaction was not found with the correct timestamp.");
                        LOGGER.error("SOLR has successfully connected to your repository  however the SOLR indexes and repository database do not match."); 
                        LOGGER.error("If this is a new or rebuilt database your SOLR indexes also need to be re-built to match the database."); 
                        LOGGER.error("You can also check your SOLR connection details in solrcore.properties.");
                        throw new AlfrescoRuntimeException("Initial transaction not found with correct timestamp");
                    }
                    else if (setSize == 1)
                    {
                        state.setCheckedFirstTransactionTime(true);
                        LOGGER.info("Verified first transaction and timestamp in index");
                    }
                    else
                    {
                        LOGGER.warn("Duplicate initial transaction found with correct timestamp");
                    }
                }
            }
        }

        // Checks that the last TxId in solr is <= last TxId in repo
        if (!state.isCheckedLastTransactionTime())
        {
            if (firstTransactions == null)
            {
                firstTransactions = client.getTransactions(null, minTxnIdRange.getFirst(), null, minTxnIdRange.getSecond(), 1);
            }
            
            setLastTxCommitTimeAndTxIdInTrackerState(firstTransactions);
            Long maxTxnCommitTimeInRepo = firstTransactions.getMaxTxnCommitTime();
            Long maxTxnIdInRepo = firstTransactions.getMaxTxnId();
            if (maxTxnCommitTimeInRepo != null && maxTxnIdInRepo != null)
            {
                Transaction maxTxInIndex = this.infoSrv.getMaxTransactionIdAndCommitTimeInIndex();
                if (maxTxInIndex.getCommitTimeMs() > maxTxnCommitTimeInRepo)
                {
                    LOGGER.error("Last transaction was found in index with timestamp later than that of repository.");
                    LOGGER.error("Max Tx In Index: " + maxTxInIndex.getId() + ", In Repo: " + maxTxnIdInRepo);
                    LOGGER.error("Max Tx Commit Time In Index: " + maxTxInIndex.getCommitTimeMs() + ", In Repo: "
                            + maxTxnCommitTimeInRepo);
                    LOGGER.error("SOLR has successfully connected to your repository  however the SOLR indexes and repository database do not match."); 
                    LOGGER.error("If this is a new or rebuilt database your SOLR indexes also need to be re-built to match the database.");
                    LOGGER.error("You can also check your SOLR connection details in solrcore.properties.");
                    throw new AlfrescoRuntimeException("Last transaction found in index with incorrect timestamp");
                }
                else
                {
                    state.setCheckedLastTransactionTime(true);
                    LOGGER.info("Verified last transaction timestamp in index less than or equal to that of repository.");
                }
            }
        }
    }
    
    private void indexTransactions() throws IOException, AuthenticationException, JSONException
    {
        long startElapsed = System.nanoTime();
        
        int docCount = 0;
        boolean requiresCommit = false;
        while (transactionsToIndex.peek() != null)
        {
            Long transactionId = transactionsToIndex.poll();
            if (transactionId != null)
            {
                Transactions transactions = client.getTransactions(null, transactionId, null, transactionId+1, 1);
                if ((transactions.getTransactions().size() > 0) && (transactionId.equals(transactions.getTransactions().get(0).getId())))
                {
                    Transaction info = transactions.getTransactions().get(0);

                    GetNodesParameters gnp = new GetNodesParameters();
                    ArrayList<Long> txs = new ArrayList<Long>();
                    txs.add(info.getId());
                    gnp.setTransactionIds(txs);
                    gnp.setStoreProtocol(storeRef.getProtocol());
                    gnp.setStoreIdentifier(storeRef.getIdentifier());
                    updateShardProperty();

                    shardProperty.ifPresent(p -> gnp.setShardProperty(p));

                    gnp.setCoreName(coreName);

                    List<Node> nodes = client.getNodes(gnp, (int) info.getUpdates());
                    for (Node node : nodes)
                    {
                        docCount++;
                        if (LOGGER.isDebugEnabled())
                        {
                            LOGGER.debug(node.toString());
                        }
                        this.infoSrv.indexNode(node, false);
                        checkShutdown();
                    }

                    // Index the transaction doc after the node - if this is not found then a reindex will be done.
                    this.infoSrv.indexTransaction(info, false);
                    LOGGER.info("INDEX ACTION - Transaction {} has been indexed", transactionId);
                    requiresCommit = true;

                    trackerStats.addTxDocs(nodes.size());

                }
                else
                {
                    LOGGER.info("INDEX ACTION - Transaction {} was not found in database, it has NOT been reindexed", transactionId);
                }
            }

            if (docCount > batchCount)
            {
                if(this.infoSrv.getRegisteredSearcherCount() < getMaxLiveSearchers())
                {
                    checkShutdown();
                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedNodeTime(docCount, endElapsed-startElapsed);
                    startElapsed = endElapsed;
                    docCount = 0;
                    requiresCommit = false;
                }
            }
        }
        if (requiresCommit || (docCount > 0))
        {
            checkShutdown();
            //this.infoSrv.commit();
            long endElapsed = System.nanoTime();
            trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
        }
    }

    private void indexNodes() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (nodesToIndex.peek() != null)
        {
            Long nodeId = nodesToIndex.poll();
            if (nodeId != null)
            {
                Node node = new Node();
                node.setId(nodeId);
                node.setStatus(SolrApiNodeStatus.UNKNOWN);
                node.setTxnId(Long.MAX_VALUE);

                this.infoSrv.indexNode(node, false);
                LOGGER.info("INDEX ACTION - Node {} has been reindexed", node.getId());
                requiresCommit = true;
            }
            checkShutdown();
        }

        if(requiresCommit) {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }

    private void reindexTransactions() throws IOException, AuthenticationException, JSONException
    {
        long startElapsed = System.nanoTime();
        int docCount = 0;
        while (transactionsToReindex.peek() != null)
        {
            Long transactionId = transactionsToReindex.poll();

            if (transactionId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByTransactionId(transactionId);

                Transactions transactions = client.getTransactions(null, transactionId, null, transactionId+1, 1);
                if ((transactions.getTransactions().size() > 0) && (transactionId.equals(transactions.getTransactions().get(0).getId())))
                {
                    Transaction info = transactions.getTransactions().get(0);
                    this.infoSrv.dirtyTransaction(info.getId());
                    GetNodesParameters gnp = new GetNodesParameters();
                    ArrayList<Long> txs = new ArrayList<Long>();
                    txs.add(info.getId());
                    gnp.setTransactionIds(txs);
                    gnp.setStoreProtocol(storeRef.getProtocol());
                    gnp.setStoreIdentifier(storeRef.getIdentifier());
                    gnp.setCoreName(coreName);
                    List<Node> nodes =   client.getNodes(gnp, (int) info.getUpdates());
                    for (Node node : nodes)
                    {
                        docCount++;
                        if (LOGGER.isDebugEnabled())
                        {
                            LOGGER.debug(node.toString());
                        }
                        this.infoSrv.indexNode(node, true);
                        checkShutdown();
                    }

                    // Index the transaction doc after the node - if this is not found then a reindex will be done.
                    this.infoSrv.indexTransaction(info, true);
                    LOGGER.info("REINDEX ACTION - Transaction {} has been reindexed", transactionId);
                }
                else
                {
                    LOGGER.info("REINDEX ACTION - Transaction {} was not found in database, it has NOT been reindexed", transactionId);
                }
            }

            if (docCount > batchCount)
            {
                if(this.infoSrv.getRegisteredSearcherCount() < getMaxLiveSearchers())
                {
                    checkShutdown();
                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedNodeTime(docCount, endElapsed-startElapsed);
                    startElapsed = endElapsed;
                    docCount = 0;
                }
            }
        }
        if (docCount > 0)
        {
            checkShutdown();
            //this.infoSrv.commit();
            long endElapsed = System.nanoTime();
            trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
        }
    }

    
    private void reindexNodes() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (nodesToReindex.peek() != null)
        {
            Long nodeId = nodesToReindex.poll();
            if (nodeId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByNodeId(nodeId);

                Node node = new Node();
                node.setId(nodeId);
                node.setStatus(SolrApiNodeStatus.UNKNOWN);
                node.setTxnId(Long.MAX_VALUE);

                this.infoSrv.indexNode(node, true);
                LOGGER.info("REINDEX ACTION - Node {} has been reindexed", node.getId());
                requiresCommit = true;
            }
            checkShutdown();
        }

        if(requiresCommit)
        {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }
    
    private void reindexNodesByQuery() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (queriesToReindex.peek() != null)
        {
            String query = queriesToReindex.poll();
            if (query != null)
            {
                this.infoSrv.reindexNodeByQuery(query);
                LOGGER.info("REINDEX ACTION - Nodes from query {} have been reindexed", query);
                requiresCommit = true;
            }
            checkShutdown();
        }

        if(requiresCommit)
        {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }


    private void purgeTransactions() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (transactionsToPurge.peek() != null)
        {
            Long transactionId = transactionsToPurge.poll();
            if (transactionId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByTransactionId(transactionId);
                requiresCommit = true;
                LOGGER.info("PURGE ACTION - Purged transactionId {}", transactionId);
            }
            checkShutdown();
        }
        
        if(requiresCommit)
        {
            checkShutdown();
        }
    }

    private void purgeNodes() throws IOException, AuthenticationException, JSONException
    {
        while (nodesToPurge.peek() != null)
        {
            Long nodeId = nodesToPurge.poll();
            if (nodeId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByNodeId(nodeId);
                LOGGER.info("PURGE ACTION - Purged nodeId {}", nodeId);
            }
            checkShutdown();
        }
    }

    /**
     *  The fromCommitTime tells getSomeTransactions() where to start, this actually fairly straight forward.
     *
     *  What makes this code so tricky to understand is the state.getTimeToStopIndexing().
     *
     *  There are two scenarios to keep in mind:
     *
     *  1) Full re-index: In this scenario the state.getTimeToStopIndexing() will never stop the indexing.
     *
     *  2) Up-to-date indexing: This is where state.getTimeToStopIndexing() gets interesting. In this scenario
     *  the Solr index is already up to date with the repo and it is tracking new transactions. The state.getTimeToStopIndexing()
     *  in this scenario causes the getSomeTransactions() call to stop returning results if it finds a transaction
     *  beyond a specific point in time. This will break out of this loop and end the tracker run.
     *
     *  The next time the metadata tracker runs the "continueState()" method applies the "hole retention"
     *  to state.getLastGoodTxCommitTimeInIndex(). This causes the state.getLastGoodTxCommitTimeInIndex() to scan
     *  for prior transactions that might have been missed.
     *  
     * @param txnsFound
     * @param lastGoodTxCommitTimeInIndex
     * @return
     */
    protected Long getTxFromCommitTime(BoundedDeque<Transaction> txnsFound, long lastGoodTxCommitTimeInIndex) {
        if (txnsFound.size() > 0)
        {
            return txnsFound.getLast().getCommitTimeMs();
        }
        else
        {
            return lastGoodTxCommitTimeInIndex;
        }
    }

    private boolean alreadyFoundTransactions(BoundedDeque<Transaction> txnsFound, Transactions transactions)
    {
        if (txnsFound.size() == 0) { return false; }

        if (transactions.getTransactions().size() == 1)
        {
            return transactions.getTransactions().get(0).getId() == txnsFound.getLast().getId();
        }
        else
        {
            HashSet<Transaction> alreadyFound = new HashSet<Transaction>(txnsFound.getDeque());
            for (Transaction txn : transactions.getTransactions())
            {
                if (!alreadyFound.contains(txn)) { return false; }
            }
            return true;
        }
    }

    protected Transactions getSomeTransactions(BoundedDeque<Transaction> txnsFound, Long fromCommitTime, long timeStep,
                int maxResults, long endTime) throws AuthenticationException, IOException, JSONException, EncoderException, NoSuchMethodException
    {

        long actualTimeStep = timeStep;

        ShardState shardstate = getShardState();
        
        Transactions transactions;
        // step forward in time until we find something or hit the time bound
        // max id unbounded
        Long startTime = fromCommitTime == null  ? 0L : fromCommitTime;
        if(startTime == 0)
        {
            return client.getTransactions(startTime,
                                          null,
                                          startTime + actualTimeStep, 
                                          null, 
                                          maxResults, 
                                          shardstate);
        }
        
        do
        {
            transactions = client.getTransactions(startTime, null, startTime + actualTimeStep, null, maxResults, shardstate);
            startTime += actualTimeStep;
            
            // If no transactions are found, advance the time window to the next available transaction commit time
            if (nextTxCommitTimeServiceAvailable && transactions.getTransactions().size() == 0)
            {
                Long nextTxCommitTime = client.getNextTxCommitTime(coreName, startTime);
                if (nextTxCommitTime != -1)
                {
                    LOGGER.info("{}-[CORE {}] Advancing transactions from {} to {}", 
                            Thread.currentThread().getId(), coreName, startTime, nextTxCommitTime);
                    transactions = client.getTransactions(nextTxCommitTime, null, nextTxCommitTime + actualTimeStep, null, maxResults, shardstate);
                }
            }

        } while (((transactions.getTransactions().size() == 0) && (startTime < endTime))
                    || ((transactions.getTransactions().size() > 0) && alreadyFoundTransactions(txnsFound, transactions)));


        return transactions;
    }
    
    /**
     * When using DB_ID_RANGE, fromCommitTime cannot be before the commit time of the first transaction
     * for the DB_ID_RANGE to be indexed and commit time of the last transaction cannot be lower than fromCommitTime.
     * When there isn't nodes in that range, -1 is returned as commit times
     * 
     * @param fromCommitTime Starting commit time to get transactions from Repository
     * @param txnsFound List of transactions previously found
     * @return List of transactions to be indexed
     * 
     * @throws NoSuchMethodException
     * @throws AuthenticationException
     * @throws IOException
     * @throws JSONException
     * @throws EncoderException
     */
    private Transactions getDBIDRangeTransactions(Long fromCommitTime, BoundedDeque<Transaction> txnsFound)
            throws NoSuchMethodException, AuthenticationException, IOException, JSONException, EncoderException
    {
        boolean shardOutOfRange = false;
            
        DBIDRangeRouter dbIdRangeRouter = (DBIDRangeRouter) docRouter;
        Pair<Long, Long> commitTimes = client.getTxIntervalCommitTime(coreName,
                dbIdRangeRouter.getStartRange(), dbIdRangeRouter.getEndRange());
        Long shardMinCommitTime = commitTimes.getFirst();
        Long shardMaxCommitTime = commitTimes.getSecond();

        // Node Range it's not still available in repository
        if (shardMinCommitTime == -1)
        {
            LOGGER.debug(
                    "{}-[CORE {}] [DB_ID_RANGE] No nodes in range [{}-{}] "
                            + "exist in the repository. Indexing only latest transaction.",
                    Thread.currentThread().getId(), coreName, dbIdRangeRouter.getStartRange(),
                    dbIdRangeRouter.getEndRange());
            shardOutOfRange = true;
        }
        if (fromCommitTime > shardMaxCommitTime)
        {
            LOGGER.debug(
                    "{}-[CORE {}] [DB_ID_RANGE] Last commit time is greater that max commit time in in range [{}-{}]. "
                            + "Indexing only latest transaction.",
                    Thread.currentThread().getId(), coreName, dbIdRangeRouter.getStartRange(),
                    dbIdRangeRouter.getEndRange());
            shardOutOfRange = true;
        }
        // Initial commit time for Node Range is greater than calculated from commit time
        if (fromCommitTime < shardMinCommitTime)
        {
            LOGGER.debug("{}-[CORE {}] [DB_ID_RANGE] Skipping transactions from {} to {}",
                    Thread.currentThread().getId(), coreName, fromCommitTime, shardMinCommitTime);
            fromCommitTime = shardMinCommitTime;
        }

        Transactions transactions = getSomeTransactions(txnsFound, fromCommitTime, TIME_STEP_1_HR_IN_MS, maxNumberOfTransactions,
                                           state.getTimeToStopIndexing());
        
        
        // When transactions are out of Shard range, only the latest transaction needs to be indexed
        // in order to preserve the state up-to-date of the MetadataTracker
        if (shardOutOfRange)
        {
            Transaction latestTransaction = new Transaction();
            latestTransaction.setCommitTimeMs(transactions.getMaxTxnCommitTime());
            latestTransaction.setId(transactions.getMaxTxnId());
            transactions = new Transactions(
                    Arrays.asList(latestTransaction),
                    transactions.getMaxTxnCommitTime(), 
                    transactions.getMaxTxnId());
        }
        
        return transactions;
    }
    
    /**
     * Remove transactions already present in SOLR index
     * 
     * @param transactions List of transactions to be indexed
     * @return List of transactions not indexed in SOLR index
     */
    private Transactions removeIndexedTransactions(Transactions transactions)
    {
        return new Transactions(transactions.getTransactions().stream()
                .filter(transaction -> {
                    try
                    {
                        boolean isInIndex = (transaction.getCommitTimeMs() <= state.getLastIndexedTxCommitTime() &&
                                infoSrv.txnInIndex(transaction.getId(), true));
                        if (LOGGER.isTraceEnabled()) 
                        {
                            LOGGER.trace("{}-[CORE {}] Skipping Transaction Id {} as it was already indexed", 
                                    Thread.currentThread().getId(), coreName, transaction.getId());
                        }
                        return !isInIndex;
                    }
                    catch (IOException e)
                    {
                        LOGGER.warn(
                                "{}-[CORE {}] Error catched while checking if Transaction Id {} was in index",
                                Thread.currentThread().getId(), coreName, transaction.getId(), e);
                        return true;
                    }
                })
                .collect(Collectors.toList()));

    }


    //fixme remove
    /**
     * Keep only transactions previous to node transaction Id 
     * 
     * @param transactions List of transactions from Repository
     * @param node Last Node indexed in the cycle
     * @return Filtered list of transactions
     */
    private Transactions filterTransactionsByNode(Transactions transactions, Node node)
    {
        return new Transactions(transactions.getTransactions().stream()
                .filter(transaction -> {
                    return transaction.getId() < node.getTxnId();
                })
                .collect(Collectors.toList()));
    }

    /**
     * Indexing new transactions from repository in batches of "transactionDocsBatchSize" size.
     * 
     * Additionally, the nodes inside a transaction batch are indexed in batches of "nodeBatchSize" size. 
     * 
     * @throws AuthenticationException
     * @throws IOException
     * @throws JSONException
     * @throws EncoderException
     */
    protected void trackTransactions() throws AuthenticationException, IOException, JSONException, EncoderException
    {
        long startElapsed = System.nanoTime();
        
        boolean upToDate = false;
        Transactions transactions;
        BoundedDeque<Transaction> txnsFound = new BoundedDeque<Transaction>(100);
        int totalUpdatedDocs = 0;
        
        LOGGER.info("{}-[CORE {}] Starting metadata tracker execution", Thread.currentThread().getId(), coreName);
        
        do
        {
            
            try
            {
                /*
                * This write lock is used to lock out the Commit Tracker. The ensures that the MetaDataTracker will
                * not be indexing content while commits or rollbacks are occurring.
                */
                getWriteLock().acquire();

                /*
                * We acquire the tracker state again here and set it globally. This is because the
                * tracker state could have been invalidated due to a rollback by the CommitTracker.
                * In this case the state will revert to the last transaction state record in the index.
                */
                this.state = getTrackerState();

                Long fromCommitTime = getTxFromCommitTime(txnsFound, 
                        state.getLastIndexedTxCommitTime() == 0 ? state.getLastGoodTxCommitTimeInIndex() : state.getLastIndexedTxCommitTime());
                
                // Get transaction list to be indexed
                if (docRouter instanceof DBIDRangeRouter && txIntervalCommitTimeServiceAvailable)
                {
                    transactions = getDBIDRangeTransactions(fromCommitTime, txnsFound);
                }
                else
                {
                    transactions = getSomeTransactions(txnsFound, fromCommitTime, TIME_STEP_1_HR_IN_MS, maxNumberOfTransactions,
                            state.getTimeToStopIndexing());                    
                }
                
                // Remove transactions already indexed
                transactions = removeIndexedTransactions(transactions);
                
                if (transactions.getTransactions().size() > 0)
                {
                    LOGGER.info("{}-[CORE {}] Found {} transactions after lastTxCommitTime {}, transactions from {} to {}", 
                            Thread.currentThread().getId(),
                            coreName, 
                            transactions.getTransactions().size(),
                            fromCommitTime,
                            transactions.getTransactions().get(0),
                            transactions.getTransactions().get(transactions.getTransactions().size() - 1));
                } 
                else 
                {
                    LOGGER.info("{}-[CORE {}] No transaction found after lastTxCommitTime {}",
                            Thread.currentThread().getId(), 
                            coreName, 
                            ((txnsFound.size() > 0) ? txnsFound.getLast().getCommitTimeMs() : state.getLastIndexedTxCommitTime()));
                }
                
                // Group the transactions in batches of transactionDocsBatchSize (or less)
                List<List<Transaction>> txBatches = new ArrayList<>();
                List<Transaction> txBatch = new ArrayList<>();
                for (Transaction info : transactions.getTransactions()) {
                    
                    if (LOGGER.isTraceEnabled()) 
                    {
                        LOGGER.trace("{}-[CORE {}] Tracking {} Transactions. Current Transaction Id to be indexed: {}", 
                                Thread.currentThread().getId(), coreName, transactions.getTransactions().size(), info.getId());
                    }

                    // Make sure we do not go ahead of where we started - we will check the holes here
                    // correctly next time
                    if (info.getCommitTimeMs() > state.getTimeToStopIndexing()) {
                        upToDate = true;
                        break;
                    }

                    txBatch.add(info);
                    if (getUpdateAndDeleteCount(txBatch) > transactionDocsBatchSize) {
                        txBatches.add(txBatch);
                        txBatch = new ArrayList<>();
                    }
                }
                if (!txBatch.isEmpty())
                {
                    txBatches.add(txBatch);
                }
                
                // Index batches of transactions and the nodes updated or deleted within the transaction
                for (List<Transaction> batch : txBatches)
                {
                    
                    // Index nodes contained in the transactions
                    int docCount =  indexBatchOfTransactions(batch, totalUpdatedDocs);
                    totalUpdatedDocs += docCount;
                    
                    // Add the transactions as found to avoid processing them again in the next iteration
                    batch.forEach(transaction -> txnsFound.add(transaction));

                    // Index the transactions
                    indexTransactionsAfterWorker(batch);
                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
                    startElapsed = endElapsed;
                }


                
                setLastTxCommitTimeAndTxIdInTrackerState(transactions);
                
            }
            catch(Exception e)
            {
                throw new IOException(e);
            }
            finally
            {
                getWriteLock().release();
            }
        
        }
        while ((transactions.getTransactions().size() > 0) && (upToDate == false));

        LOGGER.info("{}-[CORE {}] Tracked {} DOCs", Thread.currentThread().getId(), coreName, totalUpdatedDocs);
    }

    /**
     * Update latest transaction indexed in MetadataTracker state
     * @param transactions List of transactions indexed
     */
    private void setLastTxCommitTimeAndTxIdInTrackerState(Transactions transactions)
    {
        Long maxTxnCommitTime = transactions.getMaxTxnCommitTime();
        if (maxTxnCommitTime != null)
        {
            state.setLastTxCommitTimeOnServer(maxTxnCommitTime);
        }

        Long maxTxnId = transactions.getMaxTxnId();
        if (maxTxnId != null)
        {
            state.setLastTxIdOnServer(maxTxnId);
        }
    }

    /**
     * Index transactions and update state of the tracker
     * @param txsIndexed List of transactions to be indexed
     * @throws IOException
     */
    private void indexTransactionsAfterWorker(List<Transaction> txsIndexed)
                throws IOException
    {
        for (Transaction tx : txsIndexed)
        {
            infoSrv.indexTransaction(tx, true);
            // Transactions are ordered by commit time and tie-broken by tx id
            if (tx.getCommitTimeMs() > state.getLastIndexedTxCommitTime()
                    || tx.getCommitTimeMs() == state.getLastIndexedTxCommitTime()
                    && tx.getId() > state.getLastIndexedTxId())
            {
                state.setLastIndexedTxCommitTime(tx.getCommitTimeMs());
                state.setLastIndexedTxId(tx.getId());
            }
            trackerStats.addTxDocs((int) (tx.getDeletes() + tx.getUpdates()));
        }
        txsIndexed.clear();
    }

    /**
     * Return the number of updated and deleted nodes in a list of transactions
     * @param txs List of transactions
     * @return Number of updated and deleted nodes
     */
    private long getUpdateAndDeleteCount(List<Transaction> txs)
    {
        long count = 0;
        for (Transaction tx : txs)
        {
            count += (tx.getUpdates() + tx.getDeletes());
        }
        return count;
    }

    /**
     * Index a batch of transactions.
     * 
     * Updated or deleted nodes from these transactions are also packed into batches in order to get 
     * the metadata of the nodes in smaller invocations to Repository 
     * 
     * @param txBatch Batch of transactions to be indexed
     * @param indexedNodes Number of nodes indexed in this Tracker execution
     * 
     * @return Number of nodes indexed and last node indexed
     * 
     * @throws AuthenticationException
     * @throws IOException
     * @throws JSONException
     */
    private int indexBatchOfTransactions(List<Transaction> txBatch, int indexedNodes) throws AuthenticationException, IOException, JSONException, ExecutionException, InterruptedException {
        // Skip transactions without modifications (updates, deletes)
        ArrayList<Transaction> nonEmptyTxs = new ArrayList<>(txBatch.size());
        ArrayList<Long> txIds = new ArrayList<>();
        for (Transaction tx : txBatch)
        {
            if (tx.getUpdates() > 0 || tx.getDeletes() > 0)
            {
                nonEmptyTxs.add(tx);
                txIds.add(tx.getId());
            }
        }
        
        // Get Nodes Id properties for every transaction
        GetNodesParameters gnp = new GetNodesParameters();
        gnp.setTransactionIds(txIds);
        gnp.setStoreProtocol(storeRef.getProtocol());
        gnp.setStoreIdentifier(storeRef.getIdentifier());
        updateShardProperty();
        shardProperty.ifPresent(p -> gnp.setShardProperty(p));
        gnp.setCoreName(coreName);
        List<Node> nodes = client.getNodes(gnp, Integer.MAX_VALUE);

        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("{}-[CORE {}] Found {} Nodes to be indexed from Transactions: {}", Thread.currentThread().getId(),
                    coreName, nodes.size(), txIds);
        }
        
        // Group the nodes in batches of nodeBatchSize (or less)
        List<List<Node>> nodeBatches = Lists.partition(nodes, nodeBatchSize);

        Integer processedNodes = forkJoinPool.submit(() ->
                nodeBatches.parallelStream().map(batch -> {
                    new NodeIndexWorker(batch, infoSrv).run();
                    return batch.size();
                }).reduce(0, Integer::sum)).get();



        return processedNodes;
    }


    /**
     * Node Indexing class running synchronously all the tracking operations.
     */
    class NodeIndexWorker extends AbstractWorker
    {
        InformationServer infoServer;
        List<Node> nodes;

        NodeIndexWorker(List<Node> nodes, InformationServer infoServer)
        {
            this.infoServer = infoServer;
            this.nodes = nodes;
        }

        @Override
        protected void doWork() throws IOException, AuthenticationException, JSONException
        { 
            List<Node> filteredNodes = filterNodes(nodes);
            if(filteredNodes.size() > 0)
            {
                this.infoServer.indexNodes(filteredNodes, true);
            }
        }
        
        @Override
        protected void onFail(Throwable failCausedBy)
        {
        	setRollback(true, failCausedBy);
        }
        
        private List<Node> filterNodes(List<Node> nodes)
        {
            ArrayList<Node> filteredList = new ArrayList<Node>(nodes.size());
            for(Node node : nodes)
            {

                if(docRouter.routeNode(shardCount, shardInstance, node))
                {
                    filteredList.add(node);
                }
                else if (cascadeTrackerEnabled)
                {
                    if(node.getStatus() == SolrApiNodeStatus.UPDATED)
                    {
                        Node doCascade = new Node();
                        doCascade.setAclId(node.getAclId());
                        doCascade.setId(node.getId());
                        doCascade.setNodeRef(node.getNodeRef());
                        doCascade.setStatus(SolrApiNodeStatus.NON_SHARD_UPDATED);
                        doCascade.setTenant(node.getTenant());
                        doCascade.setTxnId(node.getTxnId());
                        filteredList.add(doCascade);
                    }
                    else // DELETED & UNKNOWN
                    {
                        // Make sure anything no longer relevant to this shard is deleted.
                        Node doDelete = new Node();
                        doDelete.setAclId(node.getAclId());
                        doDelete.setId(node.getId());
                        doDelete.setNodeRef(node.getNodeRef());
                        doDelete.setStatus(SolrApiNodeStatus.NON_SHARD_DELETED);
                        doDelete.setTenant(node.getTenant());
                        doDelete.setTxnId(node.getTxnId());
                        filteredList.add(doDelete);
                    }
                }
            }
            return filteredList;
        }
    }

    @Override
    public NodeReport checkNode(Long dbid)
    {
        NodeReport nodeReport = super.checkNode(dbid);

        // In DB
        GetNodesParameters parameters = new GetNodesParameters();
        parameters.setFromNodeId(dbid);
        parameters.setToNodeId(dbid);
        try
        {
            List<Node> dbnodes = client.getNodes(parameters, 1);
            if (dbnodes.size() == 1)
            {
                Node dbnode = dbnodes.get(0);
                nodeReport.setDbNodeStatus(dbnode.getStatus());
                nodeReport.setDbTx(dbnode.getTxnId());
            }
            else
            {
                nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
                nodeReport.setDbTx(-1L);
            }
        }
        catch (IOException e)
        {
            nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
            nodeReport.setDbTx(-2L);
        }
        catch (JSONException e)
        {
            nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
            nodeReport.setDbTx(-3L);
        }
        catch (AuthenticationException e1)
        {
            nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
            nodeReport.setDbTx(-4L);
        }

        return nodeReport;
    }

    public NodeReport checkNode(Node node)
    {
        return checkNode(node.getId());
    }

    public List<Node> getFullNodesForDbTransaction(Long txid)
    {
        try
        {
            GetNodesParameters gnp = new GetNodesParameters();
            ArrayList<Long> txs = new ArrayList<Long>();
            txs.add(txid);
            gnp.setTransactionIds(txs);
            gnp.setStoreProtocol(storeRef.getProtocol());
            gnp.setStoreIdentifier(storeRef.getIdentifier());
            gnp.setCoreName(coreName);
            return client.getNodes(gnp, Integer.MAX_VALUE);
        }
        catch (IOException e)
        {
            throw new AlfrescoRuntimeException("Failed to get nodes", e);
        }
        catch (JSONException e)
        {
            throw new AlfrescoRuntimeException("Failed to get nodes", e);
        }
        catch (AuthenticationException e)
        {
            throw new AlfrescoRuntimeException("Failed to get nodes", e);
        }
    }

    public IndexHealthReport checkIndex(Long toTx, Long toAclTx, Long fromTime, Long toTime)
                throws IOException, AuthenticationException, JSONException, EncoderException, NoSuchMethodException
    {
        // DB TX Count
        long firstTransactionCommitTime = 0;
        Transactions firstTransactions = client.getTransactions(null, 0L, null, 2000l, 1);
        if(firstTransactions.getTransactions().size() > 0)
        {
            Transaction firstTransaction = firstTransactions.getTransactions().get(0);
            firstTransactionCommitTime = firstTransaction.getCommitTimeMs();
        }

        IOpenBitSet txIdsInDb = infoSrv.getOpenBitSetInstance();
        Long lastTxCommitTime = Long.valueOf(firstTransactionCommitTime);
        if (fromTime != null)
        {
            lastTxCommitTime = fromTime;
        }
        long maxTxId = 0;
        Long minTxId = null;

        Transactions transactions;
        BoundedDeque<Transaction> txnsFound = new  BoundedDeque<Transaction>(100);
        long endTime = System.currentTimeMillis() + infoSrv.getHoleRetention();
        DO: do
        {
            transactions = getSomeTransactions(txnsFound, lastTxCommitTime, TIME_STEP_1_HR_IN_MS, maxNumberOfTransactions, endTime);
            for (Transaction info : transactions.getTransactions())
            {
                // include
                if (toTime != null)
                {
                    if (info.getCommitTimeMs() > toTime.longValue())
                    {
                        break DO;
                    }
                }
                if (toTx != null)
                {
                    if (info.getId() > toTx.longValue())
                    {
                        break DO;
                    }
                }

                // bounds for later loops
                if (minTxId == null)
                {
                    minTxId = info.getId();
                }
                if (maxTxId < info.getId())
                {
                    maxTxId = info.getId();
                }

                lastTxCommitTime = info.getCommitTimeMs();
                txIdsInDb.set(info.getId());
                txnsFound.add(info);
            }
        }
        while (transactions.getTransactions().size() > 0);

        return this.infoSrv.reportIndexTransactions(minTxId, txIdsInDb, maxTxId);
    }

    public void addTransactionToPurge(Long txId)
    {
        this.transactionsToPurge.offer(txId);
    }

    public void addNodeToPurge(Long nodeId)
    {
        this.nodesToPurge.offer(nodeId);
    }

    public void addTransactionToReindex(Long txId)
    {
        this.transactionsToReindex.offer(txId);
    }

    public void addNodeToReindex(Long nodeId)
    {
        nodesToReindex.offer(nodeId);
    }

    public void addTransactionToIndex(Long txId)
    {
        transactionsToIndex.offer(txId);
    }

    public void addNodeToIndex(Long nodeId)
    {
        this.nodesToIndex.offer(nodeId);
    }

    public void invalidateState() {
        super.invalidateState();
        infoSrv.clearProcessedTransactions();
    }

    /**
     * @param query
     */
    public void addQueryToReindex(String query)
    {
        this.queriesToReindex.offer(query);
    }
}

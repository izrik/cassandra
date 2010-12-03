package org.apache.cassandra.service;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.HashMultimap;
import org.junit.Test;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.RandomPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.SimpleSnitch;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.UnavailableException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConsistencyLevelTest extends CleanupHelper
{
    @Test
    public void testReadWriteConsistencyChecks() throws Exception
    {
        StorageService ss = StorageService.instance;
        final int RING_SIZE = 3;

        TokenMetadata tmd = ss.getTokenMetadata();
        tmd.clearUnsafe();
        IPartitioner partitioner = new RandomPartitioner();

        ss.setPartitionerUnsafe(partitioner);

        ArrayList<Token> endpointTokens = new ArrayList<Token>();
        ArrayList<Token> keyTokens = new ArrayList<Token>();
        List<InetAddress> hosts = new ArrayList<InetAddress>();

        Util.createInitialRing(ss, partitioner, endpointTokens, keyTokens, hosts, RING_SIZE);

        HashMultimap<InetAddress, InetAddress> hintedNodes = HashMultimap.create();


        AbstractReplicationStrategy strategy;

        for (String table : DatabaseDescriptor.getNonSystemTables())
        {
            strategy = getStrategy(table, tmd);
            StorageService.calculatePendingRanges(strategy, table);
            int replicationFactor = strategy.getReplicationFactor();
            if (replicationFactor < 2)
                continue;

            for (ConsistencyLevel c : ConsistencyLevel.values())
            {

                if (c == ConsistencyLevel.EACH_QUORUM || c == ConsistencyLevel.LOCAL_QUORUM)
                    continue;

                for (int i = 0; i < replicationFactor; i++)
                {
                    hintedNodes.clear();

                    for (int j = 0; j < i; j++)
                    {
                        hintedNodes.put(hosts.get(j), hosts.get(j));
                    }

                    IWriteResponseHandler writeHandler = strategy.getWriteResponseHandler(hosts, hintedNodes, c);

                    QuorumResponseHandler<Row> readHandler = strategy.getQuorumResponseHandler(new ReadResponseResolver(table), c);

                    boolean isWriteUnavailable = false;
                    boolean isReadUnavailable = false;
                    try
                    {
                        writeHandler.assureSufficientLiveNodes();
                    }
                    catch (UnavailableException e)
                    {
                        isWriteUnavailable = true;
                    }

                    try
                    {
                        readHandler.assureSufficientLiveNodes(hintedNodes.asMap().keySet());
                    }
                    catch (UnavailableException e)
                    {
                        isReadUnavailable = true;
                    }

                    //these should always match (in this kind of test)
                    assertTrue(isWriteUnavailable == isReadUnavailable);

                    switch (c)
                    {
                        case ALL:
                            if (isWriteUnavailable)
                                assertTrue(hintedNodes.size() < replicationFactor);
                            else
                                assertTrue(hintedNodes.size() >= replicationFactor);

                            break;
                        case ONE:
                        case ANY:
                            if (isWriteUnavailable)
                                assertTrue(hintedNodes.size() == 0);
                            else
                                assertTrue(hintedNodes.size() > 0);
                            break;
                        case QUORUM:
                            if (isWriteUnavailable)
                                assertTrue(hintedNodes.size() < (replicationFactor / 2 + 1));
                            else
                                assertTrue(hintedNodes.size() >= (replicationFactor / 2 + 1));
                            break;
                        default:
                            fail("Unhandled CL: " + c);

                    }
                }
            }
            return;
        }

        fail("Test requires at least one table with RF > 1");
    }

    private AbstractReplicationStrategy getStrategy(String table, TokenMetadata tmd) throws ConfigurationException
    {
        return AbstractReplicationStrategy.createReplicationStrategy(table,
                                                                     "org.apache.cassandra.locator.SimpleStrategy",
                                                                     tmd,
                                                                     new SimpleSnitch(),
                                                                     null);
    }

}

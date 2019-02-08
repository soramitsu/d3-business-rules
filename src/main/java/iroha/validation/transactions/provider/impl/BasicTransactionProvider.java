package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Queries;
import iroha.protocol.Queries.BlocksQuery;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.util.ObservableRxList;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final IrohaAPI irohaAPI;
  private final String accountId;
  private final KeyPair keyPair;
  private final TransactionVerdictStorage transactionVerdictStorage;
  private final ObservableRxList<Transaction> cache = new ObservableRxList<>();
  private boolean isStarted;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

  @Autowired
  public BasicTransactionProvider(IrohaAPI irohaAPI,
      String accountId,
      KeyPair keyPair,
      TransactionVerdictStorage transactionVerdictStorage) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.transactionVerdictStorage = transactionVerdictStorage;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Observable<Transaction> getPendingTransactionsStreaming() {
    if (!isStarted) {
      logger.info("Starting pending transactions streaming");
      isStarted = true;
      executorService.scheduleAtFixedRate(this::monitorIroha, 0, 2, TimeUnit.SECONDS);
    }
    return cache.getObservable();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<Block> getBlockStreaming() {
    BlocksQuery query = new BlocksQueryBuilder(accountId, Instant.now(), 1).buildSigned(keyPair);

    return irohaAPI.blocksQuery(query).map(response -> {
          logger.info(
              "New Iroha block arrived. Height " + response.getBlockResponse().getBlock().getBlockV1()
                  .getPayload().getHeight());
          return response.getBlockResponse().getBlock();
        }
    );
  }

  private void monitorIroha() {
    Queries.Query query = Query.builder(accountId, 1).getPendingTransactions().buildSigned(keyPair);
    List<Transaction> pendingTransactions = irohaAPI.query(query).getTransactionsResponse()
        .getTransactionsList();
    // Add new
    pendingTransactions.forEach(transaction -> {
          String hex = Utils.toHex(Utils.hash(transaction));
          if (!transactionVerdictStorage.isHashPresentInStorage(hex)) {
            transactionVerdictStorage.markTransactionPending(hex);
            cache.add(transaction);
          }
        }
    );
    // Remove irrelevant
    List<String> pendingHashes = pendingTransactions
        .stream()
        .map(Utils::hash)
        .map(Utils::toHex)
        .collect(Collectors.toList());
    for (int i = 0; i < cache.size(); i++) {
      Transaction tx = cache.get(i);
      if (!pendingHashes.contains(Utils.toHex(Utils.hash(tx)))) {
        cache.remove(tx);
        i--;
      }
    }
  }

  @Override
  public void close() {
    executorService.shutdownNow();
  }
}
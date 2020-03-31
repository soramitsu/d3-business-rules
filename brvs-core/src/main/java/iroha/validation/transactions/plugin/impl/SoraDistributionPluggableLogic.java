/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.plugin.PluggableLogic;
import iroha.validation.utils.ValidationUtils;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.TransactionBuilder;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Sora distribution logic processor
 */
public class SoraDistributionPluggableLogic
    extends PluggableLogic<Transaction, Map<String, BigDecimal>> {

  private static final Logger logger = LoggerFactory.getLogger(SoraDistributionPluggableLogic.class);
  private static final String COMMA_SPACES_REGEX = ",\\s*";
  private static final String PROPORTIONS_VALUE_KEY = "distribution";
  private static final String XOR_ASSET_ID = "xor#sora";
  private static final MathContext XOR_MATH_CONTEXT = new MathContext(18, RoundingMode.DOWN);
  private static final int TRANSACTION_SIZE = 9999;
  private static final String DESCRIPTION_FORMAT = "Distribution from %s";

  private final Set<String> projectAccounts;
  private final QueryAPI queryAPI;
  private final String brvsAccountId;
  private final KeyPair brvsKeypair;
  private final String infoSetterAccount;

  public SoraDistributionPluggableLogic(
      QueryAPI queryAPI,
      String projectAccounts,
      String infoSetterAccount) {
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (StringUtils.isEmpty(projectAccounts)) {
      throw new IllegalArgumentException("Project accounts must not be neither null nor empty");
    }
    if (StringUtils.isEmpty(infoSetterAccount)) {
      throw new IllegalArgumentException("Info setter account must not be neither null nor empty");
    }

    this.queryAPI = queryAPI;
    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsKeypair = queryAPI.getKeyPair();
    this.projectAccounts = new HashSet<>(Arrays.asList(projectAccounts.split(COMMA_SPACES_REGEX)));
    this.infoSetterAccount = infoSetterAccount;

    logger.info("Started distribution processor with project accounts: {}", this.projectAccounts);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<String, BigDecimal> filterAndTransform(Iterable<Transaction> sourceObjects) {
    // sums all the xor transfers per project owner
    return StreamSupport.stream(sourceObjects.spliterator(), false)
        .flatMap(
            transaction -> transaction.getPayload()
                .getReducedPayload()
                .getCommandsList()
                .stream()
        )
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .filter(command -> XOR_ASSET_ID.equals(command.getAssetId()) &&
            projectAccounts.contains(command.getSrcAccountId()))
        .collect(
            Collectors.groupingBy(
                TransferAsset::getSrcAccountId,
                Collectors.reducing(
                    BigDecimal.ZERO,
                    transfer -> new BigDecimal(transfer.getAmount()),
                    BigDecimal::add
                )
            )
        );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(Map<String, BigDecimal> processableObject) {
    processDistributions(processableObject);
  }

  /**
   * Processes committed project owners transfers and performs corresponding distributions if
   * needed
   *
   * @param transferAssetMap {@link Map} of project owner account id to aggregated volume of their
   * transfer within the block
   */
  private void processDistributions(Map<String, BigDecimal> transferAssetMap) {
    // list for batches to send after processing
    final List<Transaction> transactionList = new ArrayList<>();
    transferAssetMap.forEach((projectOwnerAccountId, transferAmount) -> {
      logger.info("Triggered distributions for {}", projectOwnerAccountId);
      SoraDistributionProportions suppliesLeft = queryProportionsForAccount(
          projectOwnerAccountId,
          brvsAccountId
      );
      if (suppliesLeft != null && suppliesLeft.finished != null && suppliesLeft.finished) {
        logger.info("No need to perform any more distributions for {}", projectOwnerAccountId);
        return;
      }
      final SoraDistributionProportions initialProportions = queryProportionsForAccount(
          projectOwnerAccountId
      );
      // if brvs hasn't set values yet
      if (suppliesLeft == null || suppliesLeft.accountProportions == null
          || suppliesLeft.accountProportions.isEmpty()) {
        logger.warn("BRVS distribution state hasn't been set yet for {}", projectOwnerAccountId);
        suppliesLeft = constructInitialAmountMap(initialProportions);
      }
      final SoraDistributionProportions finalSuppliesLeft = suppliesLeft;
      // <String -> Amount> map for the project client accounts
      final Map<String, BigDecimal> toDistributeMap = initialProportions.accountProportions
          .entrySet()
          .stream()
          .collect(
              Collectors.toMap(
                  Entry::getKey,
                  entry -> calculateAmountForDistribution(
                      entry.getValue(),
                      transferAmount,
                      finalSuppliesLeft.accountProportions.get(entry.getKey())
                  )
              )
          );
      transactionList.addAll(
          constructTransactions(
              projectOwnerAccountId,
              finalSuppliesLeft,
              toDistributeMap
          )
      );
    });
    sendDistributions(transactionList);
  }

  private void sendDistributions(List<Transaction> distributionTransactions) {
    if (!distributionTransactions.isEmpty()) {
      final Iterable<Transaction> atomicBatch = Utils.createTxAtomicBatch(
          distributionTransactions,
          brvsKeypair
      );
      final IrohaAPI irohaAPI = queryAPI.getApi();
      irohaAPI.transactionListSync(atomicBatch);
      final byte[] byteHash = Utils.hash(atomicBatch.iterator().next());
      final TxStatus txStatus = ValidationUtils.subscriptionStrategy
          .subscribe(irohaAPI, byteHash)
          .blockingLast()
          .getTxStatus();
      if (!txStatus.equals(TxStatus.COMMITTED)) {
        throw new IllegalStateException(
            "Could not perform distribution. Got transaction status: " + txStatus.name()
                + ", hash: " + Utils.toHex(byteHash)
        );
      }
      logger.info("Successfully committed distribution");
    }
  }

  private List<Transaction> constructTransactions(
      String projectOwnerAccountId,
      SoraDistributionProportions supplies,
      Map<String, BigDecimal> toDistributeMap) {
    int commandCounter = 0;
    final List<Transaction> transactionList = new ArrayList<>();
    transactionList.add(
        constructDetailTransaction(
            projectOwnerAccountId,
            supplies,
            toDistributeMap)
    );
    TransactionBuilder transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
        .builder(brvsAccountId);
    for (Map.Entry<String, BigDecimal> entry : toDistributeMap.entrySet()) {
      final BigDecimal amount = entry.getValue();
      if (amount.compareTo(BigDecimal.ZERO) > 0) {
        appendDistributionCommand(
            projectOwnerAccountId,
            entry.getKey(),
            amount,
            transactionBuilder
        );
        commandCounter++;
        if (commandCounter == TRANSACTION_SIZE) {
          transactionList.add(transactionBuilder.build().build());
          transactionBuilder = jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId);
          commandCounter = 0;
        }
      }
    }
    if (commandCounter > 0) {
      transactionList.add(transactionBuilder.build().build());
    }
    logger.debug("Constrtucted project distribution transactions: count - {}",
        transactionList.size()
    );
    return transactionList;
  }

  private void appendDistributionCommand(
      String projectOwnerAccountId,
      String clientAccountId,
      BigDecimal amount,
      TransactionBuilder transactionBuilder) {
    transactionBuilder.transferAsset(
        brvsAccountId,
        clientAccountId,
        XOR_ASSET_ID,
        String.format(DESCRIPTION_FORMAT, projectOwnerAccountId),
        amount
    );
    logger.debug("Appended distribution command: project - {}, to - {}, amount - {}",
        projectOwnerAccountId,
        clientAccountId,
        amount.toPlainString()
    );
  }

  private Transaction constructDetailTransaction(
      String projectOwnerAccountId,
      SoraDistributionProportions supplies,
      Map<String, BigDecimal> toDistributeMap) {
    final SoraDistributionProportions suppliesLeftAfterDistributions = getSuppliesLeftAfterDistributions(
        supplies,
        toDistributeMap
    );
    logger.debug(
        "Constructed supply detail transaction with status {}",
        suppliesLeftAfterDistributions.finished
    );
    return jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId)
        .setAccountDetail(
            projectOwnerAccountId,
            PROPORTIONS_VALUE_KEY,
            Utils.irohaEscape(
                ValidationUtils.gson.toJson(suppliesLeftAfterDistributions)
            )
        )
        .sign(brvsKeypair)
        .build();
  }

  private SoraDistributionProportions getSuppliesLeftAfterDistributions(
      SoraDistributionProportions supplies,
      Map<String, BigDecimal> toDistributeMap) {
    final Map<String, BigDecimal> accountProportions = supplies.accountProportions;
    final Map<String, BigDecimal> resultingSuppliesMap = accountProportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> {
                  BigDecimal subtrahend = toDistributeMap.get(entry.getKey());
                  if (subtrahend == null) {
                    subtrahend = BigDecimal.ZERO;
                  }
                  return entry.getValue().subtract(subtrahend);
                }
            )
        );
    return new SoraDistributionProportions(
        resultingSuppliesMap,
        supplies.totalSupply,
        resultingSuppliesMap.values().stream()
            .allMatch(value -> value.compareTo(BigDecimal.ZERO) == 0)
    );
  }

  /**
   * Creates an instance with initial amount limits to be distributed
   *
   * @param initialProportion initial {@link SoraDistributionProportions} set by Sora
   * @return ready to be used {@link SoraDistributionProportions} as the first calculation
   */
  private SoraDistributionProportions constructInitialAmountMap(
      SoraDistributionProportions initialProportion) {
    final BigDecimal totalSupply = initialProportion.totalSupply;
    final Map<String, BigDecimal> decimalMap = initialProportion.accountProportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> totalSupply.multiply(entry.getValue(), XOR_MATH_CONTEXT)
            )
        );

    return new SoraDistributionProportions(
        decimalMap,
        totalSupply,
        decimalMap.values().stream()
            .allMatch(value -> value.compareTo(BigDecimal.ZERO) == 0)
    );
  }

  private BigDecimal calculateAmountForDistribution(
      BigDecimal percentage,
      BigDecimal totalSupply,
      BigDecimal leftToDistribute) {
    final BigDecimal calculated = totalSupply.multiply(percentage, XOR_MATH_CONTEXT);
    if (leftToDistribute == null) {
      return calculated;
    }
    return calculated.min(leftToDistribute);
  }

  private SoraDistributionProportions queryProportionsForAccount(String accountId) {
    return queryProportionsForAccount(accountId, infoSetterAccount);
  }

  private SoraDistributionProportions queryProportionsForAccount(
      String accountId,
      String setterAccountId) {
    return ValidationUtils.gson.fromJson(
        Utils.irohaUnEscape(
            queryAPI.getAccountDetails(
                accountId,
                setterAccountId,
                PROPORTIONS_VALUE_KEY
            )
        ),
        SoraDistributionProportions.class
    );
  }

  private void sendXorAndsetNewProportionsToAccount(
      SoraDistributionProportions proportions,
      String accountId) {

  }

  public static class SoraDistributionProportions {

    // Account -> percentage from Sora
    // Account -> left in absolute measure from BRVS
    protected Map<String, BigDecimal> accountProportions;
    protected BigDecimal totalSupply;
    protected Boolean finished;

    public SoraDistributionProportions(
        Map<String, BigDecimal> accountProportions,
        BigDecimal totalSupply,
        Boolean finished) {
      this.accountProportions = accountProportions;
      this.totalSupply = totalSupply;
      this.finished = finished;
    }
  }
}

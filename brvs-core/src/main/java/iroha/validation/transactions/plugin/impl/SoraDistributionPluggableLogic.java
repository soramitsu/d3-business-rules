/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl;

import static iroha.validation.utils.ValidationUtils.advancedQueryAccountDetails;
import static iroha.validation.utils.ValidationUtils.trackHashWithLastResponseWaiting;

import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.impl.billing.BillingInfo;
import iroha.validation.rules.impl.billing.BillingInfo.BillingTypeEnum;
import iroha.validation.rules.impl.billing.BillingRule;
import iroha.validation.transactions.plugin.PluggableLogic;
import iroha.validation.utils.ValidationUtils;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
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
public class SoraDistributionPluggableLogic extends PluggableLogic<Map<String, BigDecimal>> {

  private static final Logger logger = LoggerFactory
      .getLogger(SoraDistributionPluggableLogic.class);
  private static final String COMMA_SPACES_REGEX = ",\\s*";
  public static final String DISTRIBUTION_PROPORTIONS_KEY = "distribution";
  public static final String DISTRIBUTION_FINISHED_KEY = "distribution_finished";
  private static final String SORA_DOMAIN = "sora";
  private static final String XOR_ASSET_ID = "xor#" + SORA_DOMAIN;
  private static final int XOR_PRECISION = 18;
  private static final RoundingMode XOR_ROUNDING_MODE = RoundingMode.DOWN;
  private static final MathContext XOR_MATH_CONTEXT = new MathContext(
      Integer.MAX_VALUE,
      XOR_ROUNDING_MODE
  );
  private static final int TRANSACTION_SIZE = 9999;
  private static final String DESCRIPTION_FORMAT = "Distribution from %s";
  private static final BigDecimal FEE_RATE = new BigDecimal("100");

  private final Set<String> projectAccounts;
  private final QueryAPI queryAPI;
  private final String brvsAccountId;
  private final KeyPair brvsKeypair;
  private final String infoSetterAccount;
  // for fee retrieval
  private final BillingRule billingRule;

  public SoraDistributionPluggableLogic(
      QueryAPI queryAPI,
      String projectAccounts,
      String infoSetterAccount,
      BillingRule billingRule) {
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (StringUtils.isEmpty(projectAccounts)) {
      throw new IllegalArgumentException("Project accounts must not be neither null nor empty");
    }
    if (StringUtils.isEmpty(infoSetterAccount)) {
      throw new IllegalArgumentException("Info setter account must not be neither null nor empty");
    }
    Objects.requireNonNull(billingRule, "Billing rule must not be null");

    this.queryAPI = queryAPI;
    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsKeypair = queryAPI.getKeyPair();
    this.projectAccounts = new HashSet<>(Arrays.asList(projectAccounts.split(COMMA_SPACES_REGEX)));
    this.infoSetterAccount = infoSetterAccount;
    this.billingRule = billingRule;

    logger.info("Started distribution processor with project accounts: {}", this.projectAccounts);
  }

  private <T> List<T> mergeLists(List<T> first, List<T> second) {
    final ArrayList<T> list = new ArrayList<>();
    list.addAll(first);
    list.addAll(second);
    return list;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<String, BigDecimal> filterAndTransform(Iterable<Transaction> sourceObjects) {
    // sums all the xor transfers and subtractions per project owner
    return StreamSupport
        .stream(sourceObjects.spliterator(), false)
        .filter(transaction -> projectAccounts
            .contains(
                transaction
                    .getPayload()
                    .getReducedPayload()
                    .getCreatorAccountId()
            )
        )
        .collect(
            Collectors.groupingBy(
                tx -> tx.getPayload().getReducedPayload().getCreatorAccountId(),
                Collectors.reducing(
                    BigDecimal.ZERO,
                    tx -> tx.getPayload()
                        .getReducedPayload()
                        .getCommandsList()
                        .stream()
                        .map(command -> {
                          if (command.hasSubtractAssetQuantity() &&
                              XOR_ASSET_ID.equals(
                                  command.getSubtractAssetQuantity().getAssetId())
                          ) {
                            return new BigDecimal(command.getSubtractAssetQuantity().getAmount());
                          } else if (command.hasTransferAsset() &&
                              XOR_ASSET_ID.equals(command.getTransferAsset().getAssetId())
                          ) {
                            return new BigDecimal(command.getTransferAsset().getAmount());
                          } else {
                            return BigDecimal.ZERO;
                          }
                        })
                        .reduce(BigDecimal::add)
                        .orElse(BigDecimal.ZERO),
                    BigDecimal::add
                )
            )
        )
        .entrySet()
        .stream()
        // only > 0
        .filter(entry -> entry.getValue().signum() == 1)
        .collect(
            Collectors.toMap(
                Entry::getKey,
                Entry::getValue
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

  private BigDecimal getFeeSafely() {
    final BillingInfo info = billingRule
        .getBillingInfoFor(SORA_DOMAIN, XOR_ASSET_ID, BillingTypeEnum.TRANSFER);
    if (info == null) {
      return BigDecimal.ZERO;
    }
    return info.getFeeFraction();
  }

  /**
   * Processes committed project owners transfers and performs corresponding distributions if
   * needed
   *
   * @param transferAssetMap {@link Map} of project owner account id to aggregated volume of their
   * transfer within the block
   */
  private void processDistributions(Map<String, BigDecimal> transferAssetMap) {
    if (transferAssetMap != null && !transferAssetMap.isEmpty()) {
      // map for batches to send after processing
      final Map<String, List<Transaction>> transactionMap = new HashMap<>();
      final BigDecimal fee = getFeeSafely();
      transferAssetMap.forEach((projectOwnerAccountId, transferAmount) -> {
        logger.info("Triggered distributions for {}", projectOwnerAccountId);
        final SoraDistributionFinished distributionFinished = queryDistributionsFinishedForAccount(
            projectOwnerAccountId
        );
        final boolean isFinished = Optional.ofNullable(distributionFinished)
            .map(SoraDistributionFinished::getFinished)
            .orElse(false);
        if (isFinished) {
          logger.info("No need to perform any more distributions for {}", projectOwnerAccountId);
          return;
        }
        SoraDistributionProportions suppliesLeft = queryProportionsForAccount(
            projectOwnerAccountId,
            brvsAccountId
        );
        final SoraDistributionProportions initialProportions = queryProportionsForAccount(
            projectOwnerAccountId
        );
        final boolean isProportionsEmpty = Optional.ofNullable(initialProportions)
            .map(p -> p.accountProportions)
            .map(Map::isEmpty)
            .orElse(true);
        if (isProportionsEmpty) {
          logger.warn(
              "No proportions have been set for project {}. Omitting.",
              projectOwnerAccountId
          );
          return;
        }
        // if brvs hasn't set values yet
        final boolean isBrvsProportionsEmpty = Optional.ofNullable(suppliesLeft)
            .map(p -> p.accountProportions)
            .map(Map::isEmpty)
            .orElse(true);
        if (isBrvsProportionsEmpty) {
          logger.warn("BRVS distribution state hasn't been set yet for {}", projectOwnerAccountId);
          suppliesLeft = constructInitialAmountMap(initialProportions);
        }
        final SoraDistributionProportions finalSuppliesLeft = suppliesLeft;
        final BigDecimal multipliedFee = multiplyWithRespect(fee, FEE_RATE);
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
                        finalSuppliesLeft.accountProportions.get(entry.getKey()),
                        multipliedFee
                    )
                )
            );
        transactionMap.merge(
            projectOwnerAccountId,
            constructTransactions(
                projectOwnerAccountId,
                transferAmount,
                finalSuppliesLeft,
                toDistributeMap,
                initialProportions,
                fee
            ),
            this::mergeLists
        );
      });
      sendDistributions(transactionMap);
    }
  }

  private void sendDistributions(Map<String, List<Transaction>> distributionTransactions) {
    if (distributionTransactions != null && !distributionTransactions.isEmpty()) {
      distributionTransactions.forEach((projectAccount, transactions) -> {
        if (transactions != null && !transactions.isEmpty()) {
          final Iterable<Transaction> atomicBatch = Utils.createTxAtomicBatch(
              transactions,
              brvsKeypair
          );
          final IrohaAPI irohaAPI = queryAPI.getApi();
          irohaAPI.transactionListSync(atomicBatch);
          final byte[] byteHash = Utils.hash(atomicBatch.iterator().next());
          final TxStatus txStatus = trackHashWithLastResponseWaiting(irohaAPI, byteHash)
              .getTxStatus();
          if (!txStatus.equals(TxStatus.COMMITTED)) {
            throw new IllegalStateException(
                "Could not perform distribution. Got transaction status: " + txStatus.name()
                    + ", hashes: " + StreamSupport.stream(atomicBatch.spliterator(), false)
                    .map(Utils::toHexHash).collect(Collectors.toList())
            );
          }
          logger.info("Successfully committed distribution");
        }
      });
    }
  }

  private List<Transaction> constructTransactions(
      String projectOwnerAccountId,
      BigDecimal transferAmount,
      SoraDistributionProportions supplies,
      Map<String, BigDecimal> toDistributeMap,
      SoraDistributionProportions initialProportions,
      BigDecimal fee) {
    int commandCounter = 0;
    final List<Transaction> transactionList = new ArrayList<>();
    final BigDecimal multipliedFee = multiplyWithRespect(fee, FEE_RATE);
    final SoraDistributionProportions afterDistribution = getSuppliesLeftAfterDistributions(
        supplies,
        transferAmount,
        toDistributeMap,
        initialProportions,
        multipliedFee
    );
    final SoraDistributionFinished soraDistributionFinished = new SoraDistributionFinished(
        afterDistribution.totalSupply.signum() == 0 ||
            afterDistribution.accountProportions.values().stream().allMatch(
                amount -> amount.signum() == 0
            )
    );
    transactionList.add(
        constructDetailTransaction(
            projectOwnerAccountId,
            afterDistribution,
            soraDistributionFinished
        )
    );
    transactionList.add(
        constructFeeTransaction(
            fee
        )
    );
    TransactionBuilder transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
        .builder(brvsAccountId);
    // In case it is going to finish, add all amounts left
    if (soraDistributionFinished.finished) {
      afterDistribution.accountProportions.forEach((account, amount) ->
          toDistributeMap.merge(account, amount, BigDecimal::add)
      );
    }
    for (Map.Entry<String, BigDecimal> entry : toDistributeMap.entrySet()) {
      final BigDecimal amount = entry.getValue();
      if (amount.signum() == 1) {
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
      SoraDistributionProportions suppliesLeftAfterDistributions,
      SoraDistributionFinished soraDistributionFinished) {
    return jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId)
        .setAccountDetail(
            projectOwnerAccountId,
            DISTRIBUTION_PROPORTIONS_KEY,
            Utils.irohaEscape(
                ValidationUtils.gson.toJson(suppliesLeftAfterDistributions)
            )
        )
        .setAccountDetail(
            projectOwnerAccountId,
            DISTRIBUTION_FINISHED_KEY,
            Utils.irohaEscape(
                ValidationUtils.gson.toJson(soraDistributionFinished)
            )
        )
        .sign(brvsKeypair)
        .build();
  }

  private Transaction constructFeeTransaction(
      BigDecimal amount) {
    return jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId)
        .subtractAssetQuantity(
            XOR_ASSET_ID,
            amount
        )
        .sign(brvsKeypair)
        .build();
  }

  private SoraDistributionProportions getSuppliesLeftAfterDistributions(
      SoraDistributionProportions supplies,
      BigDecimal transferAmount,
      Map<String, BigDecimal> toDistributeMap,
      SoraDistributionProportions initialProportions,
      BigDecimal fee) {
    final BigDecimal totalSupply = supplies.totalSupply;
    final Map<String, BigDecimal> resultingSuppliesMap = supplies.accountProportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> {
                  BigDecimal subtrahend = toDistributeMap.get(entry.getKey());
                  if (subtrahend == null) {
                    subtrahend = BigDecimal.ZERO;
                  }
                  final BigDecimal feeSubtrahend = multiplyWithRespect(
                      initialProportions.accountProportions.get(entry.getKey()),
                      fee
                  ).add(subtrahend);
                  return entry.getValue().subtract(feeSubtrahend).max(BigDecimal.ZERO);
                }
            )
        );
    final BigDecimal supplyWithdrawn = totalSupply.subtract(transferAmount);
    final BigDecimal supplyLeft =
        supplyWithdrawn.signum() == -1 ? BigDecimal.ZERO : supplyWithdrawn;
    return new SoraDistributionProportions(
        resultingSuppliesMap,
        supplyLeft
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
                entry -> multiplyWithRespect(totalSupply, entry.getValue())
            )
        );

    return new SoraDistributionProportions(
        decimalMap,
        totalSupply
    );
  }

  private BigDecimal calculateAmountForDistribution(
      BigDecimal percentage,
      BigDecimal transferAmount,
      BigDecimal leftToDistribute,
      BigDecimal fee) {
    final BigDecimal calculated = multiplyWithRespect(
        transferAmount,
        percentage
    );
    if (leftToDistribute == null) {
      return calculated;
    }
    return calculated.min(leftToDistribute).subtract(multiplyWithRespect(fee, percentage));
  }

  private SoraDistributionProportions queryProportionsForAccount(String accountId) {
    return queryProportionsForAccount(accountId, infoSetterAccount);
  }

  private SoraDistributionProportions queryProportionsForAccount(
      String accountId,
      String setterAccountId) {
    return advancedQueryAccountDetails(
        queryAPI,
        accountId,
        setterAccountId,
        DISTRIBUTION_PROPORTIONS_KEY,
        SoraDistributionProportions.class
    );
  }

  private SoraDistributionFinished queryDistributionsFinishedForAccount(String accountId) {
    return advancedQueryAccountDetails(
        queryAPI,
        accountId,
        brvsAccountId,
        DISTRIBUTION_FINISHED_KEY,
        SoraDistributionFinished.class
    );
  }

  private BigDecimal multiplyWithRespect(BigDecimal value, BigDecimal multiplicand) {
    return value.multiply(multiplicand, XOR_MATH_CONTEXT)
        .setScale(XOR_PRECISION, XOR_ROUNDING_MODE);
  }

  public static class SoraDistributionProportions {

    // Account -> percentage from Sora
    // Account -> left in absolute measure from BRVS
    protected Map<String, BigDecimal> accountProportions;
    protected BigDecimal totalSupply;

    public SoraDistributionProportions(
        Map<String, BigDecimal> accountProportions,
        BigDecimal totalSupply) {
      this.accountProportions = accountProportions;
      this.totalSupply = totalSupply;
    }
  }

  public static class SoraDistributionFinished {

    protected Boolean finished;

    public SoraDistributionFinished(Boolean finished) {
      this.finished = finished;
    }

    public Boolean getFinished() {
      return finished;
    }
  }
}

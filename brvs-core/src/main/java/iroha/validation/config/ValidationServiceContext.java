/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.config;

import iroha.validation.rules.RuleMonitor;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.core.provider.TransactionProvider;
import iroha.validation.transactions.core.provider.impl.util.BrvsData;
import iroha.validation.transactions.core.signatory.TransactionSigner;
import iroha.validation.transactions.startup.StartupLogic;
import iroha.validation.validators.Validator;
import java.util.List;
import java.util.Objects;

/**
 * Simple data structure used to aggregate all significant modules of the brvs in a one place
 */
public class ValidationServiceContext {

  private final Validator validator;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;
  private final RegistrationProvider registrationProvider;
  private final BrvsData brvsData;
  private final RuleMonitor ruleMonitor;
  private final List<StartupLogic> startupLogicList;

  public ValidationServiceContext(
      Validator validator,
      TransactionProvider transactionProvider,
      TransactionSigner transactionSigner,
      RegistrationProvider registrationProvider,
      BrvsData brvsData,
      RuleMonitor ruleMonitor,
      List<StartupLogic> startupLogicList) {
    Objects.requireNonNull(validator, "Validator must not be null");
    Objects.requireNonNull(transactionProvider, "Transaction provider must not be null");
    Objects.requireNonNull(transactionSigner, "Transaction signer must not be null");
    Objects.requireNonNull(registrationProvider, "Registration provider must not be null");
    Objects.requireNonNull(brvsData, "BRVS data must not be null");
    Objects.requireNonNull(startupLogicList, "Startup logic list must not be null");

    this.validator = validator;
    this.transactionProvider = transactionProvider;
    this.transactionSigner = transactionSigner;
    this.registrationProvider = registrationProvider;
    this.brvsData = brvsData;
    this.ruleMonitor = ruleMonitor;
    this.startupLogicList = startupLogicList;
  }

  public Validator getValidator() {
    return validator;
  }

  public TransactionProvider getTransactionProvider() {
    return transactionProvider;
  }

  public TransactionSigner getTransactionSigner() {
    return transactionSigner;
  }

  public RegistrationProvider getRegistrationProvider() {
    return registrationProvider;
  }

  public BrvsData getBrvsData() {
    return brvsData;
  }

  public RuleMonitor getRuleMonitor() {
    return ruleMonitor;
  }

  public List<StartupLogic> getStartupLogicList() {
    return startupLogicList;
  }
}

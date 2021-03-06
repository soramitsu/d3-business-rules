/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl.sora;

import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SetAccountDetail;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Payload;
import iroha.protocol.TransactionOuterClass.Transaction.Payload.ReducedPayload;
import iroha.validation.transactions.plugin.PluggableLogic;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Newly created project accounts registrations processor
 */
public class NewProjectReactionPluggableLogic extends PluggableLogic<Map<String, String>> {

  private final ProjectAccountProvider projectAccountProvider;

  public NewProjectReactionPluggableLogic(
      ProjectAccountProvider projectAccountProvider) {
    Objects.requireNonNull(
        projectAccountProvider,
        "ProjectAccountProvider must not be null"
    );
    this.projectAccountProvider = projectAccountProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<String, String> filterAndTransform(Block block) {
    final List<Transaction> transactions = block.getBlockV1().getPayload().getTransactionsList();
    if (transactions == null || transactions.isEmpty()) {
      return Collections.emptyMap();
    }

    final String accountsHolder = projectAccountProvider.getAccountsHolder();
    final String accountsSetter = projectAccountProvider.getAccountsSetter();

    return transactions.stream()
        .map(Transaction::getPayload)
        .map(Payload::getReducedPayload)
        .filter(it -> accountsSetter.equals(it.getCreatorAccountId()))
        .map(ReducedPayload::getCommandsList)
        .flatMap(Collection::stream)
        .filter(Command::hasSetAccountDetail)
        .map(Command::getSetAccountDetail)
        .filter(command -> accountsHolder.equals(command.getAccountId()))
        .collect(
            Collectors.toMap(
                SetAccountDetail::getKey,
                SetAccountDetail::getValue
            )
        );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(Map<String, String> processableObject) {
    if (processableObject.isEmpty()) {
      return;
    }
    processableObject.forEach(projectAccountProvider::addProjectWithDescription);
  }
}

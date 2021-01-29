/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.sora;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AssetTransferBlockedRule implements Rule {

  private final Set<String> assetsBlocked;

  public AssetTransferBlockedRule(String assetsBlocked) {
    Objects.requireNonNull(
        assetsBlocked,
        "Assets set to be blocked string must not be null"
    );

    this.assetsBlocked = Arrays.stream(assetsBlocked.split(",")).collect(Collectors.toSet());
  }

  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    final boolean containsBlocked = transaction.getPayload().getReducedPayload().getCommandsList()
        .stream()
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .map(TransferAsset::getAssetId)
        .anyMatch(assetsBlocked::contains);

    if (containsBlocked) {
      return ValidationResult.REJECTED(
          "Transfers of assets " + assetsBlocked.toString() + " are temporarily disabled"
      );
    }

    return ValidationResult.VALIDATED;
  }
}

/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl;

import static iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.ASSET_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.sora.AssetTransferBlockedRule;
import iroha.validation.verdict.Verdict;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AssetTransferBlockedRuleTest {

  private final Rule rule = new AssetTransferBlockedRule(ASSET_ID);

  private Transaction transaction;
  private TransferAsset transferAsset;
  private Command command;
  private List<Command> commands;

  @BeforeEach
  private void init() {
    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    transferAsset = mock(TransferAsset.class);

    command = mock(Command.class);
    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);

    commands = Collections.singletonList(command);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
    )
        .thenReturn(commands);
  }

  @AfterEach
  private void reset() {
    Mockito.reset(transaction, transferAsset, command);
  }

  /**
   * @given {@link AssetTransferBlockedRule} instance with blocked xor#sora transfers
   * @when {@link Transaction} with {@link Command TransferAsset} command with goodasset#sora
   * @then {@link AssetTransferBlockedRule} is satisfied by such {@link Transaction}
   */
  @Test
  void correctTransferTxVolumeRuleTest() {
    when(transferAsset.getAssetId()).thenReturn("goodasset#sora");

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link AssetTransferBlockedRule} instance with blocked xor#sora transfers
   * @when {@link Transaction} with {@link Command TransferAsset} command with xor#sora
   * @then {@link AssetTransferBlockedRule} is not satisfied by such {@link Transaction}
   */
  @Test
  void otherAssetTransferTxVolumeRuleTest() {
    when(transferAsset.getAssetId()).thenReturn(ASSET_ID);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }
}

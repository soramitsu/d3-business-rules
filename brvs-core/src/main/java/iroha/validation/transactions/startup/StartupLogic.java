/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.startup;

/**
 * Interface to add any logic being invoked on the service start
 */
public interface StartupLogic {

  /**
   * Applies a logic strictly on service startup
   */
  void apply();

}

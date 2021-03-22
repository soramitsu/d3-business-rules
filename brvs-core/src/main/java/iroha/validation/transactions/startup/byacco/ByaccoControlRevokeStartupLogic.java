/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.startup.byacco;

import static iroha.validation.utils.ValidationUtils.getDomain;

import iroha.validation.transactions.core.provider.RegisteredUsersStorage;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.startup.StartupLogic;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ByaccoControlRevokeStartupLogic implements StartupLogic {

  private static final Logger logger = LoggerFactory
      .getLogger(ByaccoControlRevokeStartupLogic.class);

  private final RegisteredUsersStorage registeredUsersStorage;
  private final RegistrationProvider registrationProvider;

  public ByaccoControlRevokeStartupLogic(
      RegisteredUsersStorage registeredUsersStorage,
      RegistrationProvider registrationProvider) {
    Objects.requireNonNull(registeredUsersStorage, "RegisteredUsersStorage must not be null");
    Objects.requireNonNull(registrationProvider, "RegistrationProvider must not be null");

    this.registeredUsersStorage = registeredUsersStorage;
    this.registrationProvider = registrationProvider;
  }

  @Override
  public void apply() {
    logger.info("Applying startup logic to revoke Byacco accounts access");
    final Set<String> userDomains = registrationProvider.getUserDomains();
    registeredUsersStorage.process(accounts -> processInternally(accounts, userDomains));
    userDomains.forEach(registeredUsersStorage::removeByDomain);
    logger.info("Finished applying startup logic to revoke Byacco accounts access");
  }

  private Collection<String> processInternally(Iterable<String> accountIds, Set<String> domains) {
    return StreamSupport.stream(accountIds.spliterator(), false)
        .filter(accountId -> domains.contains(getDomain(accountId)))
        .peek(this::revokeKeysAndQuorum)
        .collect(Collectors.toSet());
  }

  private void revokeKeysAndQuorum(String accountId) {
    registrationProvider.unRegister(accountId);
  }
}

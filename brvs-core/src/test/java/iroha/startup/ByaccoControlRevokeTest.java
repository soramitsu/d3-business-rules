/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.startup;

import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.validation.transactions.core.provider.RegisteredUsersStorage;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.startup.byacco.ByaccoControlRevokeStartupLogic;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ByaccoControlRevokeTest {

  private static final String DOMAIN = "byacco";
  private static final Set<String> DOMAINS = Collections.singleton(DOMAIN);
  private static final String USERNAME = "user";
  private static final String USER_ID = USERNAME + accountIdDelimiter + DOMAIN;
  private ByaccoControlRevokeStartupLogic logic;
  private RegisteredUsersStorage storageMock;

  @BeforeEach
  public void initMocks() {
    storageMock = new RegisteredUsersStorageMock();
    final RegistrationProvider providerMock = mock(RegistrationProvider.class);
    when(providerMock.getUserDomains()).thenReturn(DOMAINS);
    doNothing().when(providerMock).unRegister(any());
    providerMock.unRegister(USER_ID);
    logic = new ByaccoControlRevokeStartupLogic(
        storageMock,
        providerMock
    );
  }

  /**
   * @given users storage containing a byacco user
   * @when a {@link ByaccoControlRevokeStartupLogic} is executed
   * @then the storage does not contain the user anymore
   */
  @Test
  public void enabledTest() {
    storageMock.add(USER_ID);
    assertTrue(storageMock.contains(USER_ID));
    logic.apply();
    assertFalse(storageMock.contains(USER_ID));
  }

  public static class RegisteredUsersStorageMock implements RegisteredUsersStorage {

    final Set<String> accounts = new HashSet<>();

    @Override
    public void add(String accountId) {
      accounts.add(accountId);
    }

    @Override
    public boolean contains(String accountId) {
      return accounts.contains(accountId);
    }

    @Override
    public void remove(String accountId) {
      accounts.remove(accountId);
    }

    @Override
    public void removeByDomain(String domain) {
      accounts.removeIf(element -> element.endsWith(accountIdDelimiter + domain));
    }

    @Override
    public <T> Collection<T> process(Function<Iterable<String>, Collection<T>> method) {
      return method.apply(accounts);
    }
  }
}

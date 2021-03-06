/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2015 ForgeRock AS
 */
package org.opends.server.backends.jeb;

import static org.mockito.Mockito.when;
import static org.opends.server.ConfigurationMock.legacyMockCfg;

import org.opends.server.admin.std.server.JEBackendCfg;
import org.opends.server.backends.pluggable.PluggableBackendImplTestCase;
import org.testng.annotations.Test;

/** JEBackend Tester. */
@Test
public class JETestCase extends PluggableBackendImplTestCase<JEBackendCfg>
{
  @Override
  protected JEBackend createBackend()
  {
    return new JEBackend();
  }

  @Override
  protected JEBackendCfg createBackendCfg()
  {
    JEBackendCfg backendCfg = legacyMockCfg(JEBackendCfg.class);
    when(backendCfg.getBackendId()).thenReturn("JETestCase");
    when(backendCfg.getDBDirectory()).thenReturn("JETestCase");
    when(backendCfg.getDBDirectoryPermissions()).thenReturn("755");
    when(backendCfg.getDBCacheSize()).thenReturn(0L);
    when(backendCfg.getDBCachePercent()).thenReturn(20);
    when(backendCfg.getDBNumCleanerThreads()).thenReturn(2);
    when(backendCfg.getDBNumLockTables()).thenReturn(63);
    return backendCfg;
  }
}

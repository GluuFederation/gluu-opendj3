/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 */

package org.forgerock.opendj.ldap.requests;

import org.forgerock.opendj.ldap.ErrorResultException;

/**
 * An abstract unmodifiable Bind request which can be used as the basis for
 * implementing new unmodifiable authentication methods.
 *
 * @param <R>
 *          The type of Bind request.
 */
abstract class AbstractUnmodifiableBindRequest<R extends BindRequest> extends
    AbstractUnmodifiableRequest<R> implements BindRequest {

  AbstractUnmodifiableBindRequest(R impl) {
    super(impl);
  }

  public BindClient createBindClient(String serverName)
      throws ErrorResultException {
    return impl.createBindClient(serverName);
  }

  public byte getAuthenticationType() {
    return impl.getAuthenticationType();
  }

  public String getName() {
    return impl.getName();
  }
}

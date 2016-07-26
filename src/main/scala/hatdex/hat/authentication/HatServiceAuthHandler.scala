/*
 * Copyright (C) 2016 Andrius Aucinas <andrius.aucinas@hatdex.org>
 * SPDX-License-Identifier: AGPL-3.0
 *
 * This file is part of the Hub of All Things project (HAT).
 *
 * HAT is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of
 * the License.
 *
 * HAT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General
 * Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package hatdex.hat.authentication

import hatdex.hat.authentication.authenticators.{UserPassHandler, AccessTokenHandler}

trait HatServiceAuthHandler {
  def accessTokenHandler = AccessTokenHandler.AccessTokenAuthenticator(
    authenticator = HatAuthHandler.AccessTokenHandler.authenticator
  ).apply()

  def userPassHandler = UserPassHandler.UserPassAuthenticator(
    authenticator = HatAuthHandler.UserPassHandler.authenticator
  ).apply()

  def userPassApiHandler = UserPassHandler.UserPassAuthenticator(
    authenticator = HatAuthHandler.UserPassHandler.authenticator
  ).apply()
}

/*
 * Copyright (C) 2017 HAT Data Exchange Ltd
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
 *
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>
 * 3 / 2017
 */

package org.hatdex.hat.api.controllers

import java.util.UUID

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.crypto.Base64AuthenticatorEncoder
import com.mohiva.play.silhouette.impl.authenticators.{ JWTRS256Authenticator, JWTRS256AuthenticatorSettings }
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import com.mohiva.play.silhouette.test._
import org.hatdex.hat.api.HATTestContext
import org.hatdex.hat.api.models.DataDebitOwner
import org.hatdex.hat.api.service._
import org.hatdex.hat.authentication.models.HatUser
import org.hatdex.hat.phata.models.{ ApiPasswordChange, ApiPasswordResetRequest, MailTokenUser }
import org.hatdex.hat.resourceManagement.HatServer
import org.joda.time.DateTime
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.specification.BeforeAll
import play.api.Logger
import play.api.i18n.Messages
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc.Result
import play.api.test.{ FakeHeaders, FakeRequest, Helpers, PlaySpecification }
import play.mvc.Http.{ HeaderNames, MimeTypes }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class AuthenticationSpec(implicit ee: ExecutionEnv) extends PlaySpecification with Mockito with AuthenticationContext with BeforeAll {

  val logger = Logger(this.getClass)

  sequential

  def beforeAll: Unit = {
    Await.result(databaseReady, 60.seconds)
  }

  "The `publicKey` method" should {
    "Return public key of the HAT" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")

      val controller = application.injector.instanceOf[Authentication]
      val result = controller.publicKey().apply(request)

      status(result) must equalTo(OK)
      contentAsString(result) must startWith("-----BEGIN PUBLIC KEY-----\n")
    }
  }

  "The `validateToken` method" should {
    "return status 401 if authenticator but no identity was found" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")
        .withAuthenticator(LoginInfo("xing", "comedian@watchmen.com"))

      val controller = application.injector.instanceOf[Authentication]
      val result = controller.validateToken().apply(request)

      status(result) must equalTo(UNAUTHORIZED)
    }

    "Return simple success message for a valid token" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)

      val controller = application.injector.instanceOf[Authentication]
      val result = controller.validateToken().apply(request)

      status(result) must equalTo(OK)
      (contentAsJson(result) \ "message").as[String] must equalTo("Authenticated")
    }
  }

  "The `hatLogin` method" should {
    "return status 401 if authenticator but no identity was found" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")
        .withAuthenticator(LoginInfo("xing", "comedian@watchmen.com"))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = controller.hatLogin("TestService", "http://testredirect").apply(request)

      status(result) must equalTo(UNAUTHORIZED)
    }

    "return status 403 if authenticator and existing identity but wrong role" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(dataDebitUser.loginInfo)

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = controller.hatLogin("TestService", "http://testredirect").apply(request)

      status(result) must equalTo(FORBIDDEN)
    }

    "return redirect url for authenticated owner" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = controller.hatLogin("TestService", "http://testredirect").apply(request)

      status(result) must equalTo(OK)
      contentAsString(result) must contain("testredirect")
      contentAsString(result) must contain("token=")
    }
  }

  "The `accessToken` method" should {
    "return status 401 if no credentials provided" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = controller.accessToken().apply(request)

      status(result) must equalTo(UNAUTHORIZED)
    }

    "return status 401 if credentials but no matching identity" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")
        .withHeaders("username" -> "test", "password" -> "test")

      val controller = application.injector.instanceOf[Authentication]

      controller.accessToken().apply(request) must throwA[IdentityNotFoundException].await(1, 30.seconds)

    }

    "return Access Token for the authenticated user" in {
      val request = FakeRequest("GET", "http://hat.hubofallthings.net")
        .withHeaders("username" -> "hatuser", "password" -> "pa55w0rd")

      val controller = application.injector.instanceOf[Authentication]

      val encoder = new Base64AuthenticatorEncoder()
      val settings = JWTRS256AuthenticatorSettings("X-Auth-Token", None, "hat.org", Some(3.days), 3.days)

      val result: Future[Result] = controller.accessToken().apply(request)

      status(result) must equalTo(OK)
      val token = (contentAsJson(result) \ "accessToken").as[String]
      val unserialized = JWTRS256Authenticator.unserialize(token, encoder, settings)
      unserialized must beSuccessfulTry
      unserialized.get.loginInfo must be equalTo owner.loginInfo
    }
  }

  "The `passwordChangeProcess` method should" in {
    "return status 403 if not owner" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(dataDebitUser.loginInfo)
        .withJsonBody(Json.toJson(passwordChangeIncorrect))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = Helpers.call(controller.passwordChangeProcess(), request)

      status(result) must equalTo(FORBIDDEN)
    }

    "return status 403 if old password incorrect" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)
        .withJsonBody(Json.toJson(passwordChangeIncorrect))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = Helpers.call(controller.passwordChangeProcess(), request)

      status(result) must equalTo(FORBIDDEN)
    }

    "return status 400 if new password too weak" in {
      val request = FakeRequest[JsValue](
        Helpers.POST,
        "/control/v2/auth/password",
        headers = FakeHeaders(Seq((HeaderNames.ACCEPT, MimeTypes.JSON), (HeaderNames.CONTENT_TYPE, MimeTypes.JSON))),
        body = Json.toJson(passwordChangeSimple),
        remoteAddress = "hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)

      val maybeResult: Option[Future[Result]] = Helpers.route(application, request)
      //      val controller = application.injector.instanceOf[Authentication]
      //      val result: Future[Result] = Helpers.call(controller.passwordChangeProcess(), request)
      maybeResult must beSome
      val result = maybeResult.get

      status(result) must equalTo(BAD_REQUEST)
      //      contentType(result) must beSome("application/json")
      (contentAsJson(result) \ "error").as[String] must equalTo("Bad Request")
    }

    "Change password if it is sufficiently strong" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)
        .withJsonBody(Json.toJson(passwordChangeStrong))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = Helpers.call(controller.passwordChangeProcess(), request)

      status(result) must equalTo(OK)
      (contentAsJson(result) \ "message").as[String] must equalTo("Password changed")
    }
  }

  "The `handleForgotPassword` method should" in {
    "Hide the fact that email doesn't match by returning status 200" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(dataDebitUser.loginInfo)
        .withJsonBody(Json.toJson(passwordForgottenIncorrect))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = Helpers.call(controller.handleForgotPassword, request)

      status(result) must equalTo(OK)
    }

    "Send email to the owner if provided email matches" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(dataDebitUser.loginInfo)
        .withJsonBody(Json.toJson(passwordForgottenOwner))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = Helpers.call(controller.handleForgotPassword, request)

      status(result) must equalTo(OK)
      there was one(mockMailer).passwordReset(any[String], any[HatUser], any[String])(any[Messages], any[HatServer])
    }
  }

  "The `handleResetPassword` method should" in {
    "Return status 401 if no such token exists" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(dataDebitUser.loginInfo)
        .withJsonBody(Json.toJson(passwordResetStrong))

      val controller = application.injector.instanceOf[Authentication]
      val result: Future[Result] = Helpers.call(controller.handleResetPassword("nosuchtoken"), request)

      status(result) must equalTo(UNAUTHORIZED)
      (contentAsJson(result) \ "cause").as[String] must equalTo("Token does not exist")
    }

    "Return status 401 if token has expired" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)
        .withJsonBody(Json.toJson(passwordResetStrong))

      val controller = application.injector.instanceOf[Authentication]
      val tokenService = application.injector.instanceOf[MailTokenUserService]
      val tokenId = UUID.randomUUID().toString

      val result: Future[Result] = for {
        _ ← tokenService.create(MailTokenUser(tokenId, "hat@hat.org", DateTime.now().minusHours(1), isSignUp = false))
        result ← Helpers.call(controller.handleResetPassword(tokenId), request)
      } yield result

      status(result) must equalTo(UNAUTHORIZED)
      (contentAsJson(result) \ "cause").as[String] must equalTo("Token expired or invalid")
    }

    "Return status 401 if token email doesn't match owner" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)
        .withJsonBody(Json.toJson(passwordResetStrong))

      val controller = application.injector.instanceOf[Authentication]
      val tokenService = application.injector.instanceOf[MailTokenUserService]
      val tokenId = UUID.randomUUID().toString

      val result: Future[Result] = for {
        _ ← tokenService.create(MailTokenUser(tokenId, "email@hat.org", DateTime.now().plusHours(1), isSignUp = false))
        result ← Helpers.call(controller.handleResetPassword(tokenId), request)
      } yield result

      status(result) must equalTo(UNAUTHORIZED)
      (contentAsJson(result) \ "cause").as[String] must equalTo("Only HAT owner can reset their password")
    }

    "Reset password" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)
        .withJsonBody(Json.toJson(passwordResetStrong))

      val controller = application.injector.instanceOf[Authentication]
      val tokenService = application.injector.instanceOf[MailTokenUserService]
      val tokenId = UUID.randomUUID().toString
      val result: Future[Result] = for {
        _ ← tokenService.create(MailTokenUser(tokenId, "user@hat.org", DateTime.now().plusHours(1), isSignUp = false))
        result ← Helpers.call(controller.handleResetPassword(tokenId), request)
      } yield result

      logger.warn(s"reset pass response: ${contentAsJson(result)}")

      status(result) must equalTo(OK)
    }

    "Return status 401 if no owner exists (should never happen)" in {
      val request = FakeRequest("POST", "http://hat.hubofallthings.net")
        .withAuthenticator(owner.loginInfo)
        .withJsonBody(Json.toJson(passwordResetStrong))

      val controller = application.injector.instanceOf[Authentication]
      val tokenService = application.injector.instanceOf[MailTokenUserService]
      val tokenId = UUID.randomUUID().toString
      val usersService = application.injector.instanceOf[UsersService]

      val result: Future[Result] = for {
        _ ← tokenService.create(MailTokenUser(tokenId, "user@hat.org", DateTime.now().plusHours(1), isSignUp = false))
        _ ← usersService.saveUser(owner.copy(roles = Seq(DataDebitOwner("")))) // forcing owner user to a different role for the test
        result ← Helpers.call(controller.handleResetPassword(tokenId), request)
      } yield result

      status(result) must equalTo(UNAUTHORIZED)
      (contentAsJson(result) \ "cause").as[String] must equalTo("No user matching token")
    }
  }

}

trait AuthenticationContext extends HATTestContext {
  val passwordChangeIncorrect = ApiPasswordChange("some-passwords-are-better-than-others", Some("wrongOldPassword"))
  val passwordChangeSimple = ApiPasswordChange("simple", Some("pa55w0rd"))
  val passwordChangeStrong = ApiPasswordChange("some-passwords-are-better-than-others", Some("pa55w0rd"))
  val passwordResetStrong = ApiPasswordChange("some-passwords-are-better-than-others", None)

  val passwordForgottenIncorrect = ApiPasswordResetRequest("email@example.com")
  val passwordForgottenOwner = ApiPasswordResetRequest("user@hat.org")
}

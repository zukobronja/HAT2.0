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
 * 2 / 2018
 */

package org.hatdex.hat.api.service.applications

import com.google.inject.AbstractModule
import com.mohiva.play.silhouette.api.crypto.{ Base64AuthenticatorEncoder, CrypterAuthenticatorEncoder }
import com.mohiva.play.silhouette.impl.authenticators.{ JWTRS256Authenticator, JWTRS256AuthenticatorSettings }
import net.codingwell.scalaguice.ScalaModule
import org.hatdex.hat.api.HATTestContext
import org.hatdex.hat.api.models.EndpointData
import org.hatdex.hat.api.models.applications.{ Application, ApplicationStatus, HatApplication, Version }
import org.hatdex.hat.api.service.richData.{ DataDebitContractService, RichDataService }
import org.hatdex.hat.authentication.models.HatUser
import org.hatdex.hat.resourceManagement.FakeHatConfiguration
import org.joda.time.DateTime
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.specification.{ BeforeAll, BeforeEach }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.{ FakeRequest, PlaySpecification }
import play.api.{ Logger, Application => PlayApplication }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class ApplicationsServiceSpec(implicit ee: ExecutionEnv) extends PlaySpecification with Mockito with ApplicationsServiceContext with BeforeEach with BeforeAll {

  val logger = Logger(this.getClass)

  sequential

  def beforeAll: Unit = {
    Await.result(databaseReady, 60.seconds)
  }

  override def before: Unit = {
    import org.hatdex.hat.dal.Tables
    import org.hatdex.libs.dal.HATPostgresProfile.api._

    val action = DBIO.seq(
      Tables.ApplicationStatus.delete)

    Await.result(hatDatabase.run(action), 60.seconds)
  }

  "The `applicationStatus` parameterless method" should {
    "List all available applications" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val result = for {
        apps <- service.applicationStatus()(hatServer, owner, fakeRequest)
      } yield {
        apps.length must be equalTo 3
        apps.find(_.application.id == notablesApp.id) must beSome
        apps.find(_.application.id == notablesAppDebitless.id) must beSome
        apps.find(_.application.id == notablesAppIncompatible.id) must beSome
      }

      result await
    }
  }

  "The `applicationStatus` method" should {

    "Provide status for a specific application" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val result = for {
        app <- service.applicationStatus(notablesApp.id)(hatServer, owner, fakeRequest)
      } yield {
        app must beSome
        app.get.application.id must be equalTo notablesApp.id
      }

      result await
    }

    "Return `None` when application is not found by ID" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val result = for {
        app <- service.applicationStatus("randomid")(hatServer, owner, fakeRequest)
      } yield {
        app must beNone
      }

      result await
    }

    "Return `active=false` status for Internal status check apps that are not setup" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val result = for {
        app <- service.applicationStatus(notablesApp.id)(hatServer, owner, fakeRequest)
      } yield {
        app must beSome
        app.get.active must beFalse
      }

      result await
    }

    "Return `active=true` status and most recent data timestamp for active app" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val dataService = application.injector.instanceOf[RichDataService]
      val result = for {
        _ <- service.setup(HatApplication(not, false, false, None, None, None))(hatServer, owner, fakeRequest)
        _ <- dataService.saveData(
          owner.userId,
          Seq(EndpointData(notablesApp.status.recentDataCheckEndpoint.get, None, JsObject(Map("test" -> JsString("test"))), None)), skipErrors = true)
        app <- service.applicationStatus(notablesApp.id)(hatServer, owner, fakeRequest)
      } yield {
        app must beSome
        app.get.setup must beTrue
        app.get.needsUpdating must beSome(false)
        app.get.mostRecentData must beSome[DateTime]
      }

      result await
    }

    "Return `active=false` status for External status check apps that are setup but respond with wrong status" in {
      val service = application.injector.instanceOf[ApplicationsService]

      val result = for {
        app <- service.applicationStatus(notablesAppExternalFailing.id)(hatServer, owner, fakeRequest)
        setup <- service.setup(app.get)(hatServer, owner, fakeRequest)
      } yield {
        setup.setup must beTrue
        setup.active must beFalse
      }

      result await
    }

    "Return `active=true` status for External status check apps that are setup" in {
      val service = application.injector.instanceOf[ApplicationsService]

      val result = for {
        app <- service.applicationStatus(notablesAppExternal.id)(hatServer, owner, fakeRequest)
        setup <- service.setup(app.get)(hatServer, owner, fakeRequest)
      } yield {
        setup.setup must beTrue
        setup.active must beTrue
      }

      result await
    }

    "Return `active=false` status for apps where current version is not compatible with one setup" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val dataDebitService = application.injector.instanceOf[DataDebitContractService]
      val result = for {
        _ <- service.setup(HatApplication(notablesAppIncompatible, false, false, None, None, None))(hatServer, owner, fakeRequest)
        app <- service.applicationStatus(notablesAppIncompatibleUpdated.id)(hatServer, owner, fakeRequest)
      } yield {
        app must beSome
        app.get.setup must beTrue
        app.get.needsUpdating must beSome(true)
      }

      result await
    }

    "Return `active=false` status for apps where data debit has been disabled" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val dataDebitService = application.injector.instanceOf[DataDebitContractService]
      val result = for {
        app <- service.applicationStatus(notablesApp.id)(hatServer, owner, fakeRequest)
        _ <- service.setup(app.get)(hatServer, owner, fakeRequest)
        _ <- dataDebitService.dataDebitDisable(app.get.application.dataDebitId.get)
        setup <- service.applicationStatus(app.get.application.id)(hatServer, owner, fakeRequest)
      } yield {
        setup must beSome
        setup.get.setup must beTrue
        setup.get.active must beFalse
      }

      result await
    }
  }

  "The `setup` method" should {
    "Enable an application and update its status as well as enable data debit if set up" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val dataDebitService = application.injector.instanceOf[DataDebitContractService]
      val result = for {
        app <- service.applicationStatus(notablesApp.id)(hatServer, owner, fakeRequest)
        setup <- service.setup(app.get)(hatServer, owner, fakeRequest)
        dd <- dataDebitService.dataDebit(notablesApp.dataDebitId.get)
      } yield {
        setup.active must beTrue
        dd must beSome
        dd.get.activeBundle must beSome
        dd.get.activeBundle.get.bundle.name must be equalTo notablesApp.permissions.dataRequired.get.bundle.name
      }

      result await
    }
  }

  "The `disable` method" should {
    "Disable an application with associated data debit" in {
      val service = application.injector.instanceOf[ApplicationsService]
      val dataDebitService = application.injector.instanceOf[DataDebitContractService]
      val result = for {
        app <- service.applicationStatus(notablesApp.id)(hatServer, owner, fakeRequest)
        _ <- service.setup(app.get)(hatServer, owner, fakeRequest)
        setup <- service.disable(app.get)(hatServer, owner, fakeRequest)
        dd <- dataDebitService.dataDebit(app.get.application.dataDebitId.get)
      } yield {
        setup.active must beFalse
        dd must beSome
        dd.get.activeBundle must beNone
      }

      result await
    }
  }

  "The `applicationToken` method" should {
    "Create a token that includes application and its version among custom claims" in {

      val service = application.injector.instanceOf[ApplicationsService]
      val result = for {
        token <- service.applicationToken(owner, notablesApp)
      } yield {
        token.accessToken mustNotEqual ""
        val encoder = new Base64AuthenticatorEncoder()
        val settings = JWTRS256AuthenticatorSettings("X-Auth-Token", None, "hat.org", Some(3.days), 3.days)
        val unserialized = JWTRS256Authenticator.unserialize(token.accessToken, encoder, settings)

        unserialized must beSuccessfulTry
        (unserialized.get.customClaims.get \ "application").get must be equalTo JsString(notablesApp.id)
        (unserialized.get.customClaims.get \ "applicationVersion").get must be equalTo JsString(notablesApp.info.version.toString)
      }

      result await

    }
  }

}

trait ApplicationsServiceContext extends HATTestContext {
  import scala.concurrent.ExecutionContext.Implicits.global
  class CustomisedFakeModule extends AbstractModule with ScalaModule {
    def configure(): Unit = {
      bind[TrustedApplicationProvider].toInstance(new TestApplicationProvider(
        Seq(notablesApp, notablesAppDebitless, notablesAppIncompatibleUpdated, notablesAppExternal, notablesAppExternalFailing)))
    }
  }

  override lazy val application: PlayApplication = new GuiceApplicationBuilder()
    .configure(FakeHatConfiguration.config)
    .overrides(new FakeModule)
    .overrides(new CustomisedFakeModule)
    .build()

  private val sampleNotablesAppJson =
    """
      |
      |    {
      |        "id": "notables",
      |        "kind": {
      |            "url": "https://itunes.apple.com/gb/app/notables/id1338778866?mt=8",
      |            "iosUrl": "https://itunes.apple.com/gb/app/notables/id1338778866?mt=8",
      |            "kind": "App"
      |        },
      |        "info": {
      |            "version": "1.0.0",
      |            "published": true,
      |            "name": "Notables",
      |            "headline": "All your words",
      |            "description": {
      |                "text": "\n Anything you write online is your data – searches, social media posts, comments and notes.\n\n Start your notes here on Notables, where they will be stored completely privately in your HAT.\n\n Use Notables to draft and share social media posts. You can set how long they stay on Twitter or Facebook – a day, a week or a month. You can always set them back to private later: it will disappear from your social media but you won’t lose it because it’s saved in your HAT.\n\n Add images or pin locations as reminders of where you were or what you saw.\n          ",
      |                "markdown": "\n Anything you write online is your data – searches, social media posts, comments and notes.\n\n Start your notes here on Notables, where they will be stored completely privately in your HAT.\n\n Use Notables to draft and share social media posts. You can set how long they stay on Twitter or Facebook – a day, a week or a month. You can always set them back to private later: it will disappear from your social media but you won’t lose it because it’s saved in your HAT.\n\n Add images or pin locations as reminders of where you were or what you saw.\n          ",
      |                "html": "\n <p>Anything you write online is your data – searches, social media posts, comments and notes.</p>\n\n <p>Start your notes here on Notables, where they will be stored completely privately in your HAT.</p>\n\n <p>Use Notables to draft and share social media posts. You can set how long they stay on Twitter or Facebook – a day, a week or a month. You can always set them back to private later: it will disappear from your social media but you won’t lose it because it’s saved in your HAT.</p>\n\n <p>Add images or pin locations as reminders of where you were or what you saw.</p>\n          "
      |            },
      |            "dataPreview": [
      |                {
      |                    "source": "notables",
      |                    "date": {
      |                        "iso": "2018-02-15T03:52:37.000Z",
      |                        "unix": 1518666757
      |                    },
      |                    "types": [
      |                        "note"
      |                    ],
      |                    "title": {
      |                        "text": "leila.hubat.net",
      |                        "action": "private"
      |                    },
      |                    "content": {
      |                        "text": "Notes are live!"
      |                    }
      |                },
      |                {
      |                    "source": "notables",
      |                    "date": {
      |                        "iso": "2018-02-15T03:52:37.317Z",
      |                        "unix": 1518666757
      |                    },
      |                    "types": [
      |                        "note"
      |                    ],
      |                    "title": {
      |                        "text": "leila.hubat.net",
      |                        "action": "private"
      |                    },
      |                    "content": {
      |                        "text": "And I love 'em!"
      |                    }
      |                }
      |            ],
      |            "graphics": {
      |                "banner": {
      |                    "normal": ""
      |                },
      |                "logo": {
      |                    "normal": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss.png"
      |                },
      |                "screenshots": [
      |                    {
      |                        "normal": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss.jpg",
      |                        "large": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss-5.jpg"
      |                    },
      |                    {
      |                        "normal": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss-2.jpg",
      |                        "large": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss-6.jpg"
      |                    },
      |                    {
      |                        "normal": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss-3.jpg",
      |                        "large": "https://s3-eu-west-1.amazonaws.com/hubofallthings-com-dexservi-dexpublicassetsbucket-kex8hb7fsdge/notablesapp/0x0ss-7.jpg"
      |                    }
      |                ]
      |            }
      |        },
      |        "permissions": {
      |            "rolesGranted": [
      |                {
      |                    "role": "namespacewrite",
      |                    "detail": "rumpel"
      |                },
      |                {
      |                    "role": "namespaceread",
      |                    "detail": "rumpel"
      |                },
      |                {
      |                    "role": "datadebit",
      |                    "detail": "app-notables"
      |                }
      |            ],
      |            "dataRequired": {
      |                "bundle": {
      |                    "name": "notablesapp",
      |                    "bundle": {
      |                        "profile": {
      |                            "endpoints": [
      |                                {
      |                                    "endpoint": "rumpel/profile",
      |                                    "filters": [
      |                                        {
      |                                            "field": "shared",
      |                                            "operator": {
      |                                                "value": true,
      |                                                "operator": "contains"
      |                                            }
      |                                        }
      |                                    ]
      |                                }
      |                            ],
      |                            "orderBy": "dateCreated",
      |                            "ordering": "descending",
      |                            "limit": 1
      |                        },
      |                        "notables": {
      |                            "endpoints": [
      |                                {
      |                                    "endpoint": "rumpel/notablesv1",
      |                                    "mapping": {
      |                                        "name": "personal.preferredName",
      |                                        "nick": "personal.nickName",
      |                                        "photo_url": "photo.avatar"
      |                                    },
      |                                    "filters": [
      |                                        {
      |                                            "field": "shared",
      |                                            "operator": {
      |                                                "value": true,
      |                                                "operator": "contains"
      |                                            }
      |                                        }
      |                                    ]
      |                                }
      |                            ],
      |                            "orderBy": "updated_time",
      |                            "ordering": "descending",
      |                            "limit": 1
      |                        }
      |                    }
      |                },
      |                "startDate": "2018-02-15T03:52:38+0000",
      |                "endDate": "2019-02-15T03:52:38+0000",
      |                "rolling": true
      |            }
      |        },
      |        "setup": {
      |            "iosUrl": "notablesapp://notablesapphost",
      |            "kind": "External"
      |        },
      |        "status": {
      |            "compatibility": "1.0.0",
      |            "recentDataCheckEndpoint": "/rumpel/notablesv1",
      |            "kind": "Internal"
      |        }
      |    }
      |
    """.stripMargin

  import org.hatdex.hat.api.json.ApplicationJsonProtocol.applicationFormat
  val notablesApp: Application = Json.parse(sampleNotablesAppJson).as[Application]

  val notablesAppDebitless: Application = notablesApp.copy(
    id = "notables-debitless",
    permissions = notablesApp.permissions.copy(dataRequired = None))

  val notablesAppIncompatible: Application = notablesApp.copy(
    id = "notables-incompatible",
    permissions = notablesApp.permissions.copy(
      dataRequired = Some(notablesApp.permissions.dataRequired.get.copy(
        bundle = notablesApp.permissions.dataRequired.get.bundle.copy(name = "notables-incompatible-bundle")))))

  val notablesAppIncompatibleUpdated: Application = notablesAppIncompatible.copy(
    info = notablesApp.info.copy(version = Version("1.1.0")),
    status = ApplicationStatus.Internal(Version("1.1.0"), None))

  val notablesAppExternal: Application = notablesApp.copy(
    id = "notables-external",
    status = ApplicationStatus.External(Version("1.0.0"), "/status", 200, None))
  val notablesAppExternalFailing: Application = notablesApp.copy(
    id = "notables-external-failing",
    status = ApplicationStatus.External(Version("1.0.0"), "/failing", 200, None))

  implicit val fakeRequest = FakeRequest("GET", "http://hat.hubofallthings.net")
  implicit val ownerImplicit: HatUser = owner
}

class TestApplicationProvider(apps: Seq[Application])(implicit ec: ExecutionContext) extends TrustedApplicationProvider {
  def applications: Future[Seq[Application]] = Future.successful(apps)

  def application(id: String): Future[Option[Application]] = {
    applications.map(_.find(_.id == id))
  }
}
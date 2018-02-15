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
package org.hatdex.hat.api.service

import javax.inject.Inject

import com.mohiva.play.silhouette.api.Silhouette
import org.hatdex.dex.apiV2.services.DexClient
import org.hatdex.hat.api.models.applications.{ Application, ApplicationStatus, HatApplication, Version }
import org.hatdex.hat.api.models.{ AccessToken, EndpointQuery }
import org.hatdex.hat.api.service.richData.{ DataDebitContractService, RichDataDuplicateDebitException, RichDataService }
import org.hatdex.hat.authentication.HatApiAuthEnvironment
import org.hatdex.hat.authentication.models.HatUser
import org.hatdex.hat.dal.Tables
import org.hatdex.hat.dal.Tables.ApplicationStatusRow
import org.hatdex.hat.resourceManagement.HatServer
import org.hatdex.libs.dal.HATPostgresProfile.api._
import org.joda.time.DateTime
import play.api.cache.AsyncCacheApi
import play.api.libs.json.{ JsObject, JsString }
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.{ Configuration, Logger }

import scala.concurrent.Future
import scala.concurrent.duration._

class ApplicationsService @Inject() (
    wsClient: WSClient,
    configuration: Configuration,
    cache: AsyncCacheApi,
    richDataService: RichDataService,
    dataDebitContractService: DataDebitContractService,
    usersService: UsersService,
    silhouette: Silhouette[HatApiAuthEnvironment])(rec: RemoteExecutionContext)(implicit val ec: DalExecutionContext) {
  private val logger = Logger(this.getClass)

  private val dexClient = new DexClient(
    wsClient,
    configuration.underlying.getString("exchange.address"),
    configuration.underlying.getString("exchange.scheme"))

  private val dexApplicationsCacheDuration: FiniteDuration = 30.minutes

  def dexApplications: Future[Seq[Application]] = {
    cache.getOrElseUpdate("apps:dexApplications", dexApplicationsCacheDuration) {
      dexClient.applications()(rec)
    }
  }

  def dexApplication(id: String): Future[Option[Application]] = {
    dexApplications.map(_.find(_.id == id))
  }

  protected def checkDataDebit(app: Application)(implicit hatServer: HatServer): Future[Boolean] = {
    app.permissions.dataRequired
      .map { dataDebitRequest =>
        app.dataDebitId
          .map { ddId =>
            dataDebitContractService.dataDebit(ddId)(hatServer.db)
              .map(_.flatMap(_.activeBundle.map(_.bundle.name == dataDebitRequest.bundle.name))) // Check that the active bundle (if any) is the same as requested
              .map(_.getOrElse(false)) // If no data debit or active bundle - not active
          }
          .getOrElse(Future.successful(false))
      }
      .getOrElse(Future.successful(true)) // If no data debit is required - OK
  }

  /*
   * Check application status - assumes application has been set up, so only checks if remote status still ok
   */
  protected def checkStatus(app: Application)(implicit hatServer: HatServer, user: HatUser, requestHeader: RequestHeader): Future[(Boolean, Option[String])] = {
    app.status match {
      case ApplicationStatus.Internal(_, _) =>
        checkDataDebit(app).map((_, None))
        for {
          token <- applicationToken(user, app)
          dd <- checkDataDebit(app)
        } yield (dd, Some(token.accessToken))
      case ApplicationStatus.External(_, statusUrl, expectedStatus, _) =>
        logger.debug(s"Check ${app.info.name} status externally")
        for {
          token <- applicationToken(user, app)
          dd <- checkDataDebit(app)
          status <- wsClient.url(statusUrl)
            .withHttpHeaders("x-auth-token" -> token.accessToken)
            .get()
            .map(_.status == expectedStatus)
        } yield (dd || status, Some(token.accessToken))
    }
  }

  def applicationToken(user: HatUser, application: Application)(implicit hatServer: HatServer, requestHeader: RequestHeader): Future[AccessToken] = {
    val customClaims = JsObject(Map(
      "application" -> JsString(application.id),
      "applicationVersion" -> JsString(application.info.version.toString())))

    silhouette.env.authenticatorService.create(user.loginInfo)
      .map(_.copy(customClaims = Some(customClaims)))
      .flatMap(silhouette.env.authenticatorService.init)
      .map(AccessToken(_, user.userId))
  }

  private def mostRecentDataTime(app: Application)(implicit hat: HatServer): Future[Option[DateTime]] = {
    app.status match {
      case ApplicationStatus.Internal(_, Some(checkEndpoint)) =>
        richDataService.propertyDataMostRecentDate(Seq(EndpointQuery(checkEndpoint, None, None, None)))(hat.db)
      case ApplicationStatus.External(_, _, _, Some(checkEndpoint)) =>
        richDataService.propertyDataMostRecentDate(Seq(EndpointQuery(checkEndpoint, None, None, None)))(hat.db)
      case _ =>
        Future.successful(None)
    }
  }

  private def appStatus(setup: ApplicationStatusRow, app: Application)(implicit hat: HatServer, user: HatUser, requestHeader: RequestHeader): Future[HatApplication] = {
    if (setup.enabled) {
      for {
        (status, maybeToken) <- checkStatus(app)
        mostRecentData <- mostRecentDataTime(app)
      } yield {
        HatApplication(app, setup = true, active = status, maybeToken,
          Some(Version(setup.version).greaterThan(app.status.compatibility)), // Needs updating if setup version beyond compatible
          mostRecentData)
      }
    }
    else {
      // If application has been disabled, reflect in status
      Future.successful(HatApplication(app, setup = true, active = false, applicationToken = None, needsUpdating = None, mostRecentData = None))
    }
  }

  private def collectStatuses(apps: Seq[Application], setup: Seq[ApplicationStatusRow])(implicit hat: HatServer, user: HatUser, requestHeader: RequestHeader): Future[Seq[HatApplication]] = {
    val statuses = apps map { app =>
      setup.find(a => app.id == a.id)
        .map { setupApp =>
          appStatus(setupApp, app)
        }
        .getOrElse {
          Future.successful(HatApplication(app, setup = false, active = false, None, None, None))
        }
    }
    Future.sequence(statuses)
  }

  def applicationStatus(id: String)(implicit hat: HatServer, user: HatUser, requestHeader: RequestHeader): Future[Option[HatApplication]] = {
    cache.getOrElseUpdate(appCacheKey(id), dexApplicationsCacheDuration) {
      for {
        apps <- dexApplication(id)
        setup <- applicationSetupStatus(id)(hat.db)
        status <- collectStatuses(Seq(apps).flatten, Seq(setup).flatten).map(_.headOption)
      } yield {
        logger.debug(s"Got dex applications: $apps")
        logger.debug(s"Got setup applications: $setup")
        logger.debug(s"Got application statuses: $status")
        status
      }
    }
  }

  def applicationStatus()(implicit hat: HatServer, user: HatUser, requestHeader: RequestHeader): Future[Seq[HatApplication]] = {
    cache.getOrElseUpdate(s"apps:${hat.domain}", dexApplicationsCacheDuration) {
      for {
        apps <- dexApplications
        setup <- applicationSetupStatus()(hat.db)
        statuses <- collectStatuses(apps, setup)
      } yield {
        logger.debug(s"Got dex applications: $apps")
        logger.debug(s"Got setup applications: $setup")
        logger.debug(s"Got application statuses: $statuses")
        statuses
      }
    }
  }

  def setup(application: HatApplication)(implicit hat: HatServer, user: HatUser, requestHeader: RequestHeader): Future[HatApplication] = {
    // Create and enable the data debit
    val maybeDataDebitSetup = for {
      ddRequest <- application.application.permissions.dataRequired
      ddId <- application.application.dataDebitId
    } yield {
      for {
        dd <- dataDebitContractService.createDataDebit(ddId, ddRequest, user.userId)(hat.db)
          .recover({
            case _: RichDataDuplicateDebitException => dataDebitContractService.updateDataDebitBundle(ddId, ddRequest, user.userId)(hat.db)
          })
        _ <- dataDebitContractService.dataDebitEnableBundle(ddId, Some(ddRequest.bundle.name))(hat.db)
      } yield dd
    }

    // Set up the app
    for {
      _ <- maybeDataDebitSetup.getOrElse(Future.successful(())) // If data debit was there, must have been setup successfully
      _ <- applicationSetupStatusUpdate(application, enabled = true)(hat.db) // Update status
      _ <- cache.remove(appCacheKey(application))
      _ <- cache.remove(s"apps:${hat.domain}")
      app <- applicationStatus(application.application.id) // Refetch all information
        .map(_.getOrElse(throw new RuntimeException("Application information not found during setup")))
    } yield app
  }

  def disable(application: HatApplication)(implicit hat: HatServer, user: HatUser, requestHeader: RequestHeader): Future[HatApplication] = {
    for {
      _ <- application.application.dataDebitId.map(dataDebitContractService.dataDebitDisable(_)(hat.db))
        .getOrElse(Future.successful(())) // If data debit was there, disable
      _ <- applicationSetupStatusUpdate(application, enabled = false)(hat.db) // Update status
      _ <- cache.remove(appCacheKey(application))
      _ <- cache.remove(s"apps:${hat.domain}")
      app <- applicationStatus(application.application.id) // Refetch all information
        .map(_.getOrElse(throw new RuntimeException("Application information not found during setup")))
    } yield app
  }

  protected def applicationSetupStatus()(implicit db: Database): Future[Seq[ApplicationStatusRow]] = {
    val query = Tables.ApplicationStatus
    db.run(query.result)
  }

  protected def applicationSetupStatus(id: String)(implicit db: Database): Future[Option[ApplicationStatusRow]] = {
    val query = Tables.ApplicationStatus.filter(_.id === id)
    db.run(query.result).map(_.headOption)
  }

  protected def applicationSetupStatusUpdate(application: HatApplication, enabled: Boolean)(implicit db: Database): Future[ApplicationStatusRow] = {
    val status = ApplicationStatusRow(application.application.id, application.application.info.version.toString, enabled)
    val query = Tables.ApplicationStatus.insertOrUpdate(status)
    db.run(query).map(_ => status)
  }

  private def appCacheKey(application: HatApplication)(implicit hat: HatServer): String = s"apps:${hat.domain}:${application.application.id}"
  private def appCacheKey(id: String)(implicit hat: HatServer): String = s"apps:${hat.domain}:$id"
}

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
 * 4 / 2017
 */

package org.hatdex.hat.api.controllers

import java.util.UUID
import javax.inject.Inject

import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.util.Clock
import org.hatdex.hat.api.models._
import org.hatdex.hat.api.service._
import org.hatdex.hat.api.service.richData._
import org.hatdex.hat.authentication.{ HatApiAuthEnvironment, HatApiController, WithRole }
import org.hatdex.hat.resourceManagement._
import org.hatdex.hat.utils.HatBodyParsers
import play.api.cache.CacheApi
import play.api.i18n.MessagesApi
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.{ JsArray, JsValue, Json }
import play.api.mvc._
import play.api.{ Configuration, Logger }

import scala.util.control.NonFatal

class RichData @Inject() (
  val messagesApi: MessagesApi,
  configuration: Configuration,
  parsers: HatBodyParsers,
  silhouette: Silhouette[HatApiAuthEnvironment],
  clock: Clock,
  hatServerProvider: HatServerProvider,
  cache: CacheApi,
  dataService: RichDataService,
  bundleService: RichBundleService)
    extends HatApiController(silhouette, clock, hatServerProvider, configuration) with RichDataJsonFormats {

  val logger = Logger(this.getClass)

  def getEndpointData(endpoint: String, recordId: Option[UUID], orderBy: Option[String], take: Option[Int]): Action[AnyContent] =
    SecuredAction(WithRole("dataCredit", "owner")).async { implicit request =>
      val query = cache.get[List[EndpointQuery]](endpoint)
        .getOrElse(List(EndpointQuery(endpoint, None, None, None)))
      val data = dataService.propertyData(query, orderBy, take.getOrElse(1000))
      data.map(d => Ok(Json.toJson(d)))
    }

  def saveEndpointData(endpoint: String): Action[JsValue] =
    SecuredAction(WithRole("dataCredit", "owner")).async(parsers.json[JsValue]) { implicit request =>
      val response = request.body match {
        case array: JsArray =>
          val values = array.value.map(EndpointData(endpoint, None, _, None))
          dataService.saveData(request.identity.userId, values) map { saved =>
            Created(Json.toJson(saved))
          }
        case value: JsValue =>
          val values = Seq(EndpointData(endpoint, None, value, None))
          dataService.saveData(request.identity.userId, values) map { saved =>
            Created(Json.toJson(saved.head))
          }
      }

      response recover {
        case e: RichDataDuplicateException =>
          BadRequest(Json.toJson(ErrorMessage("Duplicate Data", s"Could not insert data - ${e.getMessage}")))
        case e: RichDataServiceException =>
          BadRequest(Json.toJson(ErrorMessage("Bad Request", s"Could not insert data - ${e.getMessage}")))
      }
    }

  def saveBatchData: Action[Seq[EndpointData]] =
    SecuredAction(WithRole("dataCredit", "owner")).async(parsers.json[Seq[EndpointData]]) { implicit request =>
      val response = dataService.saveData(request.identity.userId, request.body) map { saved =>
        Created(Json.toJson(saved))
      }
      response recover {
        case e: RichDataDuplicateException =>
          BadRequest(Json.toJson(ErrorMessage("Duplicate Data", s"Could not insert data - ${e.getMessage}")))
        case e: RichDataServiceException =>
          BadRequest(Json.toJson(ErrorMessage("Bad Request", s"Could not insert data - ${e.getMessage}")))
      }
    }

  def registerCombinator(combinator: String): Action[Seq[EndpointQuery]] =
    SecuredAction(WithRole("dataCredit", "owner")).async(parsers.json[Seq[EndpointQuery]]) { implicit request =>
      bundleService.saveCombinator(combinator, request.body) map { _ =>
        Created(Json.toJson(SuccessResponse(s"Endpoint $combinator registered")))
      }
    }

  def getCombinatorData(combinator: String, recordId: Option[UUID], orderBy: Option[String], take: Option[Int]): Action[AnyContent] =
    SecuredAction(WithRole("dataCredit", "owner")).async { implicit request =>
      val result = for {
        query <- bundleService.combinator(combinator).map(_.get)
        data <- dataService.propertyData(query, orderBy, take.getOrElse(1000))
      } yield data

      result map { d =>
        Ok(Json.toJson(d))
      } recover {
        case NonFatal(_) =>
          NotFound(Json.toJson(ErrorMessage("Combinator Not Found", s"Combinator $combinator not found")))
      }
    }

  def linkDataRecords(records: Seq[UUID]): Action[AnyContent] =
    SecuredAction(WithRole("dataCredit", "owner")).async { implicit request =>
      dataService.saveRecordGroup(request.identity.userId, records) map { _ =>
        Created(Json.toJson(SuccessResponse(s"Grouping registered")))
      } recover {
        case RichDataMissingException(message, _) =>
          BadRequest(Json.toJson(ErrorMessage("Data Missing", s"Could not link records: $message")))
      }
    }

  def deleteDataRecords(records: Seq[UUID]): Action[AnyContent] =
    SecuredAction(WithRole("dataCredit", "owner")).async { implicit request =>
      dataService.deleteRecords(request.identity.userId, records) map { _ =>
        Ok(Json.toJson(SuccessResponse(s"All records deleted")))
      } recover {
        case RichDataMissingException(message, _) =>
          BadRequest(Json.toJson(ErrorMessage("Data Missing", s"Could not delete records: $message")))
      }
    }

  def updateRecords(): Action[Seq[EndpointData]] =
    SecuredAction(WithRole("dataCredit", "owner")).async(parsers.json[Seq[EndpointData]]) { implicit request =>
      dataService.updateRecords(request.identity.userId, request.body) map { saved =>
        Created(Json.toJson(saved))
      } recover {
        case RichDataMissingException(message, _) =>
          BadRequest(Json.toJson(ErrorMessage("Data Missing", s"Could not update records: $message")))
      }
    }

  def registerBundle(bundleId: String): Action[Map[String, PropertyQuery]] =
    SecuredAction(WithRole("dataCredit", "owner")).async(parsers.json[Map[String, PropertyQuery]]) { implicit request =>
      bundleService.saveBundle(EndpointDataBundle(bundleId, request.body))
        .map { _ =>
          Created(Json.toJson(SuccessResponse(s"Bundle $bundleId registered")))
        }
    }

  def fetchBundle(bundleId: String): Action[AnyContent] =
    SecuredAction(WithRole("dataCredit", "owner")).async { implicit request =>
      val result = for {
        bundle <- bundleService.bundle(bundleId).map(_.get)
        data <- dataService.bundleData(bundle)
      } yield data

      result map { d =>
        Ok(Json.toJson(d))
      } recover {
        case NonFatal(_) =>
          NotFound(Json.toJson(ErrorMessage("Bundle Not Found", s"Bundle $bundleId not found")))
      }
    }

}

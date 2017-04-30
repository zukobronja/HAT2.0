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

package org.hatdex.hat.api.service.monitoring

import java.util.UUID

import akka.stream.Materializer
import org.hatdex.hat.api.models._
import org.hatdex.hat.authentication.models.HatUser
import org.hatdex.hat.resourceManagement.FakeHatConfiguration
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{ JsValue, Json }
import play.api.test.PlaySpecification
import play.api.{ Application, Logger }

class EndpointSubscriberServiceSpec(implicit ee: ExecutionEnv) extends PlaySpecification with Mockito with EndpointSubscriberServiceContext {

  val logger = Logger(this.getClass)

  sequential

  "The `matchesBundle` method" should {
    "Trigger when endpoint query with no filters matches" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None, None, None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Not trigger when endpoint no query matches" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("anothertest", None, None, None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beFalse
    }

    "Trigger when endpoint query with `Contains` filter matches" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None, Some(Seq(
          EndpointQueryFilter("object", None, FilterOperator.Contains(simpleJsonFragment)))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Trigger when endpoint query with `Contains` filter matches for equality" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None,
          Some(Seq(
            EndpointQueryFilter(
              "object.objectField",
              None,
              FilterOperator.Contains(Json.toJson("objectFieldValue"))))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Trigger when endpoint query with `Contains` filter matches for array containment" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None,
          Some(Seq(
            EndpointQueryFilter("object.objectFieldArray", None,
              FilterOperator.Contains(Json.toJson("objectFieldArray2"))))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Trigger when endpoint query with `Contains` filter matches for array intersection" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None,
          Some(Seq(
            EndpointQueryFilter("object.objectFieldArray", None,
              FilterOperator.Contains(Json.parse("""["objectFieldArray2", "objectFieldArray3"]"""))))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Trigger when endpoint query with `DateTimeExtract` filter matches" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None,
          Some(Seq(
            EndpointQueryFilter(
              "date_iso",
              Some(FieldTransformation.DateTimeExtract("hour")),
              FilterOperator.Between(Json.toJson(14), Json.toJson(17))))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Trigger when endpoint query with `TimestampExtract` filter matches" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None,
          Some(Seq(
            EndpointQueryFilter(
              "date",
              Some(FieldTransformation.TimestampExtract("hour")),
              FilterOperator.Between(Json.toJson(14), Json.toJson(17))))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must beTrue
    }

    "Throw an error for text search field transformation" in {
      val service = application.injector.instanceOf[EndpointSubscriberService]

      val query = Map(
        "test" -> PropertyQuery(List(EndpointQuery("test", None,
          Some(Seq(
            EndpointQueryFilter(
              "anotherField",
              Some(FieldTransformation.Searchable()),
              FilterOperator.Find(Json.toJson("anotherFieldValue"))))), None)), None, 3))

      service.matchesBundle(simpleEndpointData, query) must throwA[EndpointQueryException]
    }
  }

}

trait EndpointSubscriberServiceContext extends Scope {
  // Setup default users for testing
  val owner = HatUser(UUID.randomUUID(), "hatuser", Some("pa55w0rd"), "hatuser", "owner", enabled = true)

  lazy val application: Application = new GuiceApplicationBuilder()
    .configure(FakeHatConfiguration.config)
    .build()

  implicit lazy val materializer: Materializer = application.materializer

  val simpleJson: JsValue = Json.parse(
    """
      | {
      |   "field": "value",
      |   "date": 1492699047,
      |   "date_iso": "2017-04-20T14:37:27+00:00",
      |   "anotherField": "anotherFieldValue",
      |   "object": {
      |     "objectField": "objectFieldValue",
      |     "objectFieldArray": ["objectFieldArray1", "objectFieldArray2", "objectFieldArray3"],
      |     "objectFieldObjectArray": [
      |       {"subObjectName": "subObject1", "subObjectName2": "subObject1-2"},
      |       {"subObjectName": "subObject2", "subObjectName2": "subObject2-2"}
      |     ]
      |   }
      | }
    """.stripMargin)

  val simpleJsonFragment: JsValue = Json.parse(
    """
      | {
      |     "objectField": "objectFieldValue",
      |     "objectFieldObjectArray": [
      |       {"subObjectName": "subObject1", "subObjectName2": "subObject1-2"},
      |       {"subObjectName": "subObject2", "subObjectName2": "subObject2-2"}
      |     ]
      | }
    """.stripMargin)

  val simpleEndpointData = EndpointData("test", Some(UUID.randomUUID()), simpleJson, None)
}
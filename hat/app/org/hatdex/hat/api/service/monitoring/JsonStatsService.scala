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

import javax.inject.Inject

import org.hatdex.hat.api.models.{ EndpointData, User }
import org.hatdex.hat.api.service.{ DalExecutionContext, StatsReporter }
import org.hatdex.hat.utils.Utils
import play.api.libs.json._

import scala.collection.immutable.HashMap

class JsonStatsService @Inject() (statsReporter: StatsReporter) extends DalExecutionContext {
  protected[service] def countJsonPaths(data: JsValue, path: Seq[String] = Seq()): HashMap[String, Long] = {
    data match {
      case v: JsArray =>
        val newPath = path.dropRight(1) :+ (path.lastOption.getOrElse("") + "[]")
        Utils.mergeMap(v.value.map(countJsonPaths(_, newPath)))((v1, v2) => v1 + v2)

      case v: JsObject =>
        val temp = v.fields map {
          case (key, value) =>
            countJsonPaths(value, path :+ key)
        }
        Utils.mergeMap(temp)((v1, v2) => v1 + v2)

      case _: JsValue => HashMap(path.mkString(".") -> 1L)
    }
  }

  protected[service] def countEndpointData(data: EndpointData): HashMap[String, HashMap[String, Long]] = {
    val counts = HashMap(data.endpoint -> countJsonPaths(data.data))
    val linkedCounts = data.links map { links =>
      links.map(countEndpointData)
    }
    val allCounts = linkedCounts.getOrElse(Seq()) :+ counts
    Utils.mergeMap(allCounts)((v1, v2) => Utils.mergeMap(Seq(v1, v2))((v1, v2) => v1 + v2))
  }

  def endpointDataCounts(data: Seq[EndpointData], user: User, logEntry: String): Iterable[InboundDataStats] = {
    val counts = data.map(countEndpointData)
    val combined = Utils.mergeMap(counts)((v1, v2) => Utils.mergeMap(Seq(v1, v2))((v1, v2) => v1 + v2))
    combined map {
      case (endpoint, eCounts) =>
        InboundDataStats(endpoint, logEntry, user, eCounts)
    }
  }
}

case class InboundDataStats(
  endpoint: String,
  logEntry: String,
  user: User,
  counts: HashMap[String, Long])

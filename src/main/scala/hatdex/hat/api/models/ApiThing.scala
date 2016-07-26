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
package hatdex.hat.api.models

import hatdex.hat.dal.Tables.ThingsThingRow

/**
 * API format of the Thing, with only the name as the mandatory field
 */
case class ApiThing(
    id: Option[Int],
    name: String,
    staticProperties: Option[Seq[ApiPropertyRelationshipStatic]],
    dynamicProperties: Option[Seq[ApiPropertyRelationshipDynamic]],
    things: Option[Seq[ApiThingRelationship]],
    people: Option[Seq[ApiPersonRelationship]])

object ApiThing {
  def fromDbModel(entity: ThingsThingRow) : ApiThing = {
    new ApiThing(Some(entity.id), entity.name, None, None, None, None)
  }
}

case class ApiThingRelationship(relationshipType: String, thing: ApiThing)
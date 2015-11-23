package graphnodes

import java.util.Date

case class F_Album(name: String, description: String,
                    dateOfCreation: Date,
                    ownerID: BigInt,
                    images: List[BigInt])

object F_Album {
  val nameString = "name"
  val descriptionString = "description"
  val ownerString = "owner"
}
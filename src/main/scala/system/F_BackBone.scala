package system

import java.security.SecureRandom
import java.text.SimpleDateFormat

import akka.actor.{Actor, ActorLogging, Props}
import spray.http.{HttpCookie, HttpRequest}
import system.workers.{F_PageProfileHandler, F_PictureHandler, F_UserHandler}

import scala.collection.mutable.Map


class F_BackBone extends Actor with ActorLogging {
  import system.F_BackBone._

  type F_AlbumE = String
  type F_PageE = String
  type F_Picture = String
  type F_Post = String
  type F_UserE = String
  type F_UserProfile = String

  //NOTE better naming needed if distributed
  val f_pictureHandler = context.actorOf(F_PictureHandler.props(self), "pictuere_handler")
  val f_userHandler = context.actorOf(F_UserHandler.props(self), "user_handler")
  val f_pageProfileHandler = context.actorOf(F_PageProfileHandler.props(self), "page_profile_hanlder")

  def receive: Receive = {
    //GET functions
    case GetUserList =>
      f_userHandler forward GetUserList

    case GetUserInfo(id) =>
      f_userHandler forward GetUserInfo(id)

    case GetPageInfo(id) =>
      f_pageProfileHandler forward GetPageInfo(id)

    case GetProfileInfo(id) =>
      f_pageProfileHandler forward GetProfileInfo(id)

    case GetPictureInfo(id) =>
      f_pictureHandler forward GetPictureInfo(id)

    case GetAlbumInfo(id) =>
      f_pictureHandler forward GetAlbumInfo(id)

    case GetImage(id) =>
      f_pictureHandler forward GetImage(id)

    case GetPostInfo(id) =>
      f_pageProfileHandler forward GetPostInfo(id)

    //POST functions
    case UpdateUserData(id, req) =>
      f_userHandler forward UpdateUserData(id, req)

    case UpdatePageData(id, req) =>
      f_pageProfileHandler forward UpdatePageData(id, req)

    case UpdateProfileData(id, req) =>
      f_pageProfileHandler forward UpdateProfileData(id, req)

    case UpdatePostData(id, req) =>
      f_pageProfileHandler forward UpdatePostData(id, req)

    case UpdateImageData(id, req) =>
      f_pictureHandler forward UpdateImageData(id, req)

    case UpdateAlbumData(id, req) =>
      f_pictureHandler forward UpdateAlbumData(id, req)

    case RequestFriend(id, req) =>
      f_userHandler forward RequestFriend(id, req)

    case HandleFriendRequest(id, req) =>
      f_userHandler forward HandleFriendRequest(id, req)

    case RemoveFriend(id, req) =>
      f_userHandler forward RemoveFriend(id, req)

    case SetUpAuthenticateUser(id, req) =>
      f_userHandler forward SetUpAuthenticateUser(id, req)

    //PUT functions
    case PutImage(image) =>
      f_pictureHandler forward PutImage(image)

    case CreateUser(req) =>
      f_userHandler forward CreateUser(req)

    case CreatePage(req) =>
      f_pageProfileHandler forward CreatePage(req)

    case CreateAlbum(req) =>
      f_pictureHandler forward CreateAlbum(req)

    case CreatePost(req) =>
      f_pageProfileHandler forward CreatePost(req)

    //DELETE functions
    case DeleteUser(id) =>
      f_userHandler forward DeleteUser(id)

    case DeletePage(id) =>
      f_pageProfileHandler forward DeletePage(id)

    case DeletePicture(id) =>
      f_pictureHandler forward DeletePicture(id)

    case DeleteAlbum(id) =>
      f_pictureHandler forward DeleteAlbumMessage(id, defaultOverride = false)

    case DeletePost(id) =>
      f_pageProfileHandler forward DeletePost(id)

    //InterSystem messages
    case VerifyAuthenticationCookie(id, request) =>
      f_userHandler forward VerifyAuthenticationCookie(id, request)

    case CreateUserProfile(userID) =>
      f_pageProfileHandler forward CreateUserProfile(userID)

    case DeleteUserProfile(userID) =>
      f_pageProfileHandler forward DeleteUserProfile(userID)

    case CreateDefaultAlbum(ownerID) =>
      f_pictureHandler forward CreateDefaultAlbum(ownerID)
  }
}

object F_BackBone {
  def props = Props[F_BackBone]

  case object GetUserList

  sealed trait GetInfo //for id doesnt exist make sure to throw a failure with that in the message, this will make the future respond like this
  //To complete the future with an exception you need send a Failure message to the sender. This is not done automatically when an actor throws an exception while processing a message. akka.actor.Status.Failure(exception)
  case class GetUserInfo(id: BigInt) extends GetInfo
  case class GetPageInfo(id: BigInt) extends GetInfo
  case class GetProfileInfo(id: BigInt) extends GetInfo
  case class GetPictureInfo(id: BigInt) extends GetInfo
  case class GetAlbumInfo(id: BigInt) extends GetInfo
  case class GetImage(id: BigInt) extends GetInfo
  case class GetPostInfo(id: BigInt) extends GetInfo

  sealed trait PostInfo
  case class UpdateUserData(id: BigInt, httpRequest: HttpRequest) extends PostInfo
  case class UpdatePageData(id: BigInt, httpRequest: HttpRequest) extends PostInfo
  case class UpdateProfileData(id: BigInt, httpRequest: HttpRequest) extends PostInfo
  case class UpdateImageData(id: BigInt, httpRequest: HttpRequest) extends PostInfo
  case class UpdateAlbumData(id: BigInt, httpRequest: HttpRequest) extends PostInfo
  case class UpdatePostData(id: BigInt, httpRequest: HttpRequest) extends PostInfo
  case class RequestFriend(requesterID: BigInt, httpRequest: HttpRequest) extends PostInfo //id from, query to
  case class HandleFriendRequest(acceptorID: BigInt, httpRequest: HttpRequest) extends PostInfo //id acceptor, query requester
  case class RemoveFriend(userID: BigInt, httpRequest: HttpRequest) extends PostInfo //restful id is remover, request is removed
  case class JoinPage(pageID: BigInt, httpRequest: HttpRequest) extends PostInfo //query contains ID of user to add
  //special authentication stuff
  case class SetUpAuthenticateUser(userID: BigInt, httpRequest: HttpRequest) extends PostInfo

  sealed trait PutInfo { val httpRequest: HttpRequest } //note: you can use the routing DSL parameter seq to extract parameters!
  case class PutImage(httpRequest: HttpRequest) extends PutInfo//must send the original sender back the JSON object of the created image
  case class CreateUser(httpRequest: HttpRequest) extends PutInfo //create user user arguments stored in httprequest and return new user JSON, they need a default profile, album, and unfilled fields for name etc
  case class CreatePage(httpRequest: HttpRequest) extends PutInfo //create page and return JSON
  case class CreateAlbum(httpRequest: HttpRequest) extends PutInfo //create picture and return JSON
  case class CreatePost(httpRequest: HttpRequest) extends PutInfo

  sealed trait DeleteInfo
  case class DeleteUser(id: BigInt) extends DeleteInfo
  case class DeletePage(id: BigInt) extends DeleteInfo
  case class DeletePicture(id: BigInt) extends DeleteInfo
  case class DeleteAlbum(id: BigInt) extends DeleteInfo //will not delete default album, deletes all pictures in album
  case class DeleteAlbumMessage(id: BigInt, defaultOverride: Boolean = false)
  case class DeletePost(id: BigInt) extends DeleteInfo

  //System messages and functions
  case class VerifyAuthenticationCookie(userID: BigInt, cookie: HttpCookie)
  case class CreateUserProfile(userID: BigInt) //replies with user profile id
  case class DeleteUserProfile(profileID: BigInt)
  case class CreateDefaultAlbum(ownerID: BigInt)

  val dateFormatter = new SimpleDateFormat("'M'MM'D'dd'Y'yyyy")

  implicit val randomIDGenerator = new SecureRandom()

  /**
   * Generatess a unique secure random ID for the map
   * @param map  map to check secure random ID against for uniquness
   * @return
   */
  def getUniqueRandomBigInt(map: Map[BigInt, _]): BigInt = {
    def isUnique(x: BigInt) = !map.contains(x)

    val x = BigInt(256, randomIDGenerator) //use 256 bits b/c sha256 does so that is low on collisions right?
    if(isUnique(x)) x
    else getUniqueRandomBigInt(map)
  }
}

package system

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.event.LoggingAdapter
import akka.util.Timeout
import spray.can.Http
import spray.http.{HttpResponse, HttpRequest}
import spray.routing._
import spray.http.StatusCodes._

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.pattern.ask

import system.F_BackBone._

import scala.util.{Failure, Success}

import language.postfixOps

class F_Listener(backBoneActor: ActorRef, connectionHandler: ActorRef) extends Actor with F_ListenerService with ActorLogging {
  override def backbone: ActorRef = backBoneActor

  def actorRefFactory = context

  context.watch(connectionHandler)

  log.debug("beginning user log\n")

  def receive = runRoute(route) orElse {
    case Terminated(_) =>
      context.stop(self)
    case _ =>
      log.debug("F_Listener does not recognize this message type")
  }
}

object F_Listener {
  def props(backbone: ActorRef, connectionHandler: ActorRef) = Props(new F_Listener(backbone, connectionHandler))
}

trait F_ListenerService extends HttpService {
  def log: LoggingAdapter
  def backbone: ActorRef

  implicit def executionContext = actorRefFactory.dispatcher

  val timeout = 5 seconds

  implicit val timeout2 = Timeout(timeout)

  //NOTE: All IDs should be sent in HEX
  val extractRequestContext = extract(x => x)
  val route: Route =  {
    host("localhost", "www.fakebook.com") {
      pathSingleSlash {
        complete("pong")
      } ~
        pathPrefix("users") {
            path("newuser") {
                put {
                  detach() {
                    extractRequestContext { request => complete(genericPut(CreateUser(request.request))) }
                  }
                }
            } ~
              get {
                detach() {
                  extractRequestContext { request => complete(genericGet(request, GetUserInfo)) }
                }
              } ~
              post {
                pathPrefix("request") {
                  detach() {
                    extractRequestContext { request => complete(genericPost(request, RequestFriend)) }
                  }
                } ~
                  pathPrefix("remove") {
                    detach() {
                      extractRequestContext { request => complete(genericPost(request, RemoveFriend)) }
                    }
                  } ~
                  detach() {
                    extractRequestContext { request => complete(genericPost(request, HandleFriendRequest)) }
                  }
              } ~
              delete {
                detach() {
                  extractRequestContext { request => complete(genericDelete(request, DeleteUser)) }
                }
              }
        } ~
        pathPrefix("data") {
          get {
            detach() {
              extractRequestContext { request => complete(getImage(request)) }
            } //URIs for actual data like pictures
          } ~
            path("uploadimage") {
              put {
                detach() {
                  extractRequestContext { request => complete(genericPut(PutImage(request.request))) }
                }
              }
            }
        } ~
        pathPrefix("page") {
          get {
            detach() {
              extractRequestContext { request => complete(genericGet(request, GetPageInfo)) }
            }
          } ~
            post {
              detach() {
                extractRequestContext { request => complete(genericPost(request, UpdatePageData)) }
              }
            } ~
            delete {
              detach() {
                extractRequestContext { request => complete(genericDelete(request, DeletePage)) }
              }
            }
          path("newpage") {
            put {
              detach() {
                extractRequestContext { request => complete(genericPut(CreatePage(request.request))) }
              }
            }
          }
        } ~
        pathPrefix("profile") {
          get {
            detach() {
              extractRequestContext { request => complete(genericGet(request, GetProfileInfo)) }
            }
          } ~ //no need for "createprofile" they are created with the user and can be accessed through the JSON returned with that creation
            post {
              detach() {
                extractRequestContext { request => complete(genericPost(request, UpdateProfileData)) }
              }
            } //no need to delete profile, deleted with user
        } ~
        pathPrefix("picture") {
          get {
            detach() {
              extractRequestContext { request => complete(genericGet(request, GetPictureInfo)) }
            }
          } ~ //same as for profile, when you upload an image the picture JSON is created
            post {
              detach() {
                extractRequestContext { request => complete(genericPost(request, UpdateImageData)) }
              }
            } ~
            delete {
              detach() {
                extractRequestContext { request => complete(genericDelete(request, DeletePicture)) }
              }
            }
        } ~
        pathPrefix("post") {
          get {
            detach() {
              extractRequestContext { request => complete(genericGet(request, GetPostInfo)) }
            }
          } ~
            post {
              detach() {
                extractRequestContext { request => complete(genericPost(request, UpdatePostData)) }
              }
            } ~
            put {
              detach() {
                extractRequestContext { request => complete(genericPut(CreatePost(request.request))) }
              }
            } ~
            delete {
              detach() {
                extractRequestContext { request => complete(genericDelete(request, DeletePost)) }
              }
            }
        } ~
        pathPrefix("album") {
          get {
            detach() {
              extractRequestContext { request => complete(genericGet(request, GetAlbumInfo)) }
            }
          } ~
            post {
              detach() {
                extractRequestContext { request => complete(genericPost(request, UpdateAlbumData)) }
              }
            } ~
            delete {
              detach() {
                extractRequestContext { request => complete(genericDelete(request, DeleteAlbum)) }
              }
            } ~
            path("createalbum") {
              put {
                detach() {
                  extractRequestContext { request => complete(genericPut(CreateAlbum(request.request))) }
                }
              }
            }
        }
    }
  }

  /**
   * goes inside of a spray routing "get" and completes the passed message to the backbone given the id
   * it internally converts the remaining request path to a bigint, if this fails it completes with a failure
   * @param req the reques who contains the string to be converted to bigint as an ID
   * @param messageConstructor the case class message (constructor but can be passed with sugar) to compose the bigint into
   * @return returns a route for thed spray routing to go through and side effects the complete needed
   */
  def genericGet(req: RequestContext, messageConstructor: (BigInt) => GetInfo) = {
    val id = req.unmatchedPath.toString

    if (!id.contains("/")) { //if this is the last element only
      try {
        val idBig = BigInt(id, 16)
        Await.ready((backbone ? messageConstructor.apply(idBig)).mapTo[String], timeout).value.get match {
          case Success(entityJson) =>
            log.info("get completed successfully: " + messageConstructor + " " + "for " + idBig)
            HttpResponse(OK, entityJson)
          case Failure(ex) =>
            log.error(ex, "get failed: " + messageConstructor + " for " + idBig)
            HttpResponse(InternalServerError, "Request could not be completed: \n" + ex.getMessage)
        }
      } catch {
        case e: NumberFormatException =>
          log.info("illegally formatted id requested: " + id)
          HttpResponse(BadRequest, "Numbers are formatted incorrectly")
        case ex: TimeoutException =>
          HttpResponse(ServiceUnavailable, "The server is under heavy load and cannot currently process your request")
      }
    }
    else {
      //log.debug("uri not formatted correctly for a get")
      HttpResponse(NotFound, "The requested URI cannot be serviced")
    }
  }

  /**
   * same as above but for posts, I treid to write a more generic function to repeat rewriting code but it ended up just not being worth the thought
   * @param req request who contains id to parse to bigint and the parameters
   * @param messageConstructor the message to send
   * @return
   */
  def genericPost(req: RequestContext, messageConstructor: (BigInt, HttpRequest) => PostInfo) = {
    val id = req.unmatchedPath.toString

    if (!id.contains("/")) {
      try {
        val idBig = BigInt(id, 16)
        Await.ready((backbone ? messageConstructor.apply(idBig, req.request)).mapTo[String], timeout).value.get match {
          case Success(newEntityJson) =>
            log.info("post completed successfully: " + messageConstructor + " for " + idBig)
            HttpResponse(OK, newEntityJson)
          case Failure(ex) =>
            log.error(ex, "get failed: " + messageConstructor + " for " + idBig)
            HttpResponse(InternalServerError, "Request could not be completed: \n" + ex.getMessage)
        }
      } catch {
        case ex: NumberFormatException =>
          log.info("illegally formatted id requested: " + id)
          HttpResponse(BadRequest, "Numbers are formatted incorrectly")
        case ex: TimeoutException =>
          HttpResponse(ServiceUnavailable, "The server is under heavy load and cannot currently process your request")
      }
    }
    else {
      //log.debug("uri not formatted correctly for a post")
      HttpResponse(NotFound, "The requested URI cannot be serviced")
    }
  }

  /**
   * same as above except no need to parse a special message since the path tells all and this is for putting, so the fully constructed message gets passed here
   * @param message constructed message to send to the backbone for handling
   * @return
   */
  def genericPut(message: PutInfo) = {
    try {
      Await.ready((backbone ? message).mapTo[String], timeout).value.get match {
        case Success(newEntityJson) =>
          log.info("put completed successfully: " + message)
          HttpResponse(OK, newEntityJson)
        case Failure(ex) =>
          log.error(ex, "put failed: " + ex.getMessage)
          HttpResponse(InternalServerError, "Error putting entity: " + ex.getMessage)
      }
    } catch {
      case ex: TimeoutException =>
        HttpResponse(ServiceUnavailable, "The server is under heavy load and cannot currently process your request")
    }
  }

  /**
   * almost identical to get, should probobly only be one function
   * @param req identical
   * @param messageConstructor identical
   * @return route
   */
  def genericDelete(req: RequestContext, messageConstructor: (BigInt) => DeleteInfo) = {
    val id = req.unmatchedPath.toString

    if (!id.contains("/")) {
      try {
        val idBig = BigInt(id, 16)
        Await.ready((backbone ? messageConstructor.apply(idBig)).mapTo[String], timeout).value.get match {
          case Success(newEntityJson) =>
            log.info("delete completed successfully: " + messageConstructor + " for " + idBig)
            HttpResponse(OK, newEntityJson)
          case Failure(ex) =>
            log.error(ex, "get failed: " + messageConstructor + " for " + idBig)
            HttpResponse(InternalServerError, "Request could not be completed: \n" + ex.getMessage)
        }
      } catch {
        case e: NumberFormatException =>
          log.info("illegally formatted id requested: " + id)
          HttpResponse(BadRequest, "Numbers are formatted incorrectly")
        case ex: TimeoutException =>
          HttpResponse(ServiceUnavailable, "The server is under heavy load and cannot currently process your request")
      }
    }
    else {
      //log.debug("uri not formatted correctly for delete")
      HttpResponse(NotFound, "The requested URI cannot be serviced")
    }
  }

  /**
   * Gets image and streams it back from file
   * @param req request context
   * @return something?
   */
  def getImage(req: RequestContext) = {
    val id = req.unmatchedPath.toString

    if (!id.contains("/")) { //if this is the last element only
      try {
        val idBig = BigInt(id, 16)
        Await.ready((backbone ? GetImage(idBig)).mapTo[Array[Byte]], timeout).value.get match {
          case Success(image) =>
            log.info("get completed successfully: " + GetImage + " " + "for " + idBig)
            HttpResponse(OK, image)
          case Failure(ex) =>
            log.error(ex, "get failed: " + GetImage + " for " + idBig)
            HttpResponse(InternalServerError, "Request could not be completed: \n" + ex.getMessage)
        }
      } catch {
        case e: NumberFormatException =>
          log.info("illegally formatted id requested: " + id)
          HttpResponse(BadRequest, "Numbers are formatted incorrectly")
        case ex: TimeoutException =>
          HttpResponse(ServiceUnavailable, "The server is under heavy load and cannot currently process your request")
      }
    }
    else HttpResponse(NotFound, "The requested URI cannot be serviced")
  }
}

/*ideas for speed improvements:
parse out arguments before passing to backbone (might help with scaling to distributed system)
genericDelete, Post, Put, and Get are all pretty similar, some functional composition is probobly possible, esp for delete and get
 */
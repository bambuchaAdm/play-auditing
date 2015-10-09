/*
 * Copyright 2015 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.play.audit.http.connector

import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.model.{AuditEvent, MergedDataEvent}
import uk.gov.hmrc.play.connectors.Connector
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.http.logging.{ConnectionTracing, LoggingDetails}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.http.ws.WSHttpResponse

import scala.concurrent.{ExecutionContext, Future}

trait AuditEventFailureKeys {
  private val EventMissed = "DS_EventMissed"
  val LoggingAuditRequestFailureKey : String = EventMissed + "_AuditFailureResponse"
  val LoggingAuditFailureResponseKey : String = EventMissed + "_AuditRequestFailure"
}

object AuditEventFailureKeys extends AuditEventFailureKeys

sealed trait AuditResult
object AuditResult {
  case object Success extends AuditResult
  case object Disabled extends AuditResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with AuditResult
}

trait AuditConnector extends Connector with AuditEventFailureKeys with ConnectionTracing {

  def auditingConfig: AuditingConfig

  protected def callAuditConsumer(url:String , body: JsValue)(implicit hc: HeaderCarrier, ec : ExecutionContext): Future[HttpResponse] =
    withTracing("Post", url) {
      buildRequest(url).post(body).map(new WSHttpResponse(_))(ec)
    }

  protected def logError(s: String) = Logger.warn(s)

  protected def logError(s: String, t: Throwable) = Logger.warn(s, t)

  def sendEvent(event: AuditEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] =
    sendEvent(auditingConfig.consumer.map(_.singleEventUrl), Json.toJson(event))

  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] =
    sendEvent(auditingConfig.consumer.map(_.mergedEventUrl), Json.toJson(event))

  def sendLargeMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier()): Future[AuditResult] =
    sendEvent(auditingConfig.consumer.map(_.largeMergedEventUrl), Json.toJson(event))

  private def sendEvent(urlOption: Option[String], body: JsValue)(implicit hc: HeaderCarrier) = {
    if (auditingConfig.enabled) {
      val url = urlOption.getOrElse( throw new Exception("Missing event consumer URL") )
      handleResult(callAuditConsumer(url, body), body).map { _ => AuditResult.Success }
    } else {
      Logger.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
      Future.successful(AuditResult.Disabled)
    }
  }

  protected[connector] def handleResult(resultF: Future[HttpResponse], body: JsValue)(implicit ld: LoggingDetails): Future[HttpResponse] = {
    resultF
      .recoverWith { case t =>
        val message = makeFailureMessage(body)
        logError(message, t)
        Future.failed(AuditResult.Failure(message, Some(t)))
      }
      .map { response =>
        checkResponse(body, response) match {
          case Some(error) =>
            logError(error)
            throw AuditResult.Failure(error)
          case None => response
        }
      }
  }

  private[connector] def makeFailureMessage(body: JsValue): String = s"$LoggingAuditRequestFailureKey : audit item : $body"

  private[connector] def checkResponse(body: JsValue, response: HttpResponse): Option[String] = {
    if (response.status >= 300) Some(s"$LoggingAuditFailureResponseKey : status code : ${response.status} : audit item : $body")
    else None
  }
}
object AuditConnector {
  def apply(config: AuditingConfig) = new AuditConnector { def auditingConfig = config }
}

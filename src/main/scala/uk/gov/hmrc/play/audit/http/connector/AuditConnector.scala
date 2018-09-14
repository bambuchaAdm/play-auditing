/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsObject, Json, Writes}
import uk.gov.hmrc.audit.{HandlerResult, handler, serialiser}
import uk.gov.hmrc.audit.handler.{AuditHandler, DatastreamHandler, DisabledAuditHandler, LoggingHandler}
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.model.{DataEvent, ExtendedDataEvent, MergedDataEvent}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.{ExecutionContext, Future}

sealed trait AuditResult
object AuditResult {
  case object Success extends AuditResult
  case object Disabled extends AuditResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with AuditResult

  def fromHandlerResult(result: HandlerResult): AuditResult = {
    result match {
      case HandlerResult.Success =>
        Success
      case HandlerResult.Rejected =>
        Failure("Event was actively rejected")
      case HandlerResult.Disabled =>
        AuditResult.Disabled
      case HandlerResult.Failure =>
        Failure("Event sending failed")
    }
  }
}

class DefaultAuditConnector(auditingConfig: AuditingConfig) extends AuditConnector {

  val defaultConnectionTimeout = 5000
  val defaultRequestTimeout = 5000

  val auditHandler = {
    if (auditingConfig.enabled) {
      val consumer: Consumer = auditingConfig.consumer.getOrElse(
        throw new Exception("the datastream base uri must be provided (usually in application.conf)")
      )
      new DatastreamHandler(consumer.baseUri.protocol, consumer.baseUri.host,
        consumer.baseUri.port, s"/${consumer.singleEventUri}", defaultConnectionTimeout, defaultRequestTimeout)
    } else {
      DisabledAuditHandler
    }
  }

  val auditSource = auditingConfig.auditSource

}

trait AuditConnector {

  def loggingConnector: AuditHandler = LoggingHandler
  def auditHandler:AuditHandler
  def auditSource:String
  def auditSerialiser: AuditSerialiserLike = AuditSerialiser

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def sendExplicitAudit(auditType: String, detail: Map[String, String])(implicit hc: HeaderCarrier, ec: ExecutionContext):Unit =
    sendExplicitAudit(auditType, Json.toJson(detail).as[JsObject])

  def sendExplicitAudit[T](auditType: String, detail: T)(implicit hc: HeaderCarrier, ec: ExecutionContext, writes: Writes[T]):Unit =
    sendExplicitAudit(auditType, Json.toJson(detail).as[JsObject])

  def sendExplicitAudit(auditType: String, detail: JsObject)(implicit hc: HeaderCarrier, ec: ExecutionContext):Unit = {
    sendExtendedEvent(ExtendedDataEvent(
      auditSource = auditSource,
      auditType = auditType,
      eventId = UUID.randomUUID().toString,
      tags = hc.toAuditTags(),
      detail = detail,
      generatedAt = DateTimeUtils.now
    ))
  }

  def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled(auditSerialiser.serialise(event.copy(tags=hc.appendToDefaultTags(event.tags))))
  }

  def sendExtendedEvent(event: ExtendedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled(auditSerialiser.serialise(event.copy(tags=hc.appendToDefaultTags(event.tags))))
  }

  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled(auditSerialiser.serialise(event))
  }

  private def ifEnabled(event: String)(implicit hc: HeaderCarrier, ec : ExecutionContext): Future[AuditResult] = {
    send(event) map (result => AuditResult.fromHandlerResult(result))
  }

  private def send(event: String)(implicit hc:HeaderCarrier, ec: ExecutionContext): Future[HandlerResult] = Future {
    try {
      val result: HandlerResult = auditHandler.sendEvent(event)
      result match {
        case HandlerResult.Success =>
          result
        case HandlerResult.Rejected =>
          result
        case HandlerResult.Disabled =>
          result
        case HandlerResult.Failure =>
          loggingConnector.sendEvent(event)
      }
    } catch {
      case e: Throwable =>
        log.error("Error in handler code", e)
        HandlerResult.Failure
    }
  }
}

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

package uk.gov.hmrc.audit.handler

import org.slf4j.LoggerFactory
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.http.HeaderCarrier

trait AuditHandler {
  def sendEvent(event: String)(implicit hc:HeaderCarrier): HandlerResult
}

object DisabledAuditHandler extends AuditHandler {

  private val log = LoggerFactory.getLogger(getClass)

  override def sendEvent(event: String)(implicit hc: HeaderCarrier): HandlerResult = {
    log.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
    HandlerResult.Disabled
  }

}
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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.audit.handler.AuditHandler
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.SessionId
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.AuditResult._
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

case class MyExampleAudit(userType:String, vrn:String)

class AuditConnectorSpec extends WordSpecLike with MustMatchers with ScalaFutures with MockitoSugar with OneInstancePerTest {

  import scala.concurrent.ExecutionContext.Implicits.global

  val mockAuditHandler: AuditHandler = mock[AuditHandler]
  val mockLoggingHandler: AuditHandler = mock[AuditHandler]

  def testAuditConnector = new AuditConnector {
    override def auditHandler: AuditHandler = mockAuditHandler
    override def loggingConnector: AuditHandler = mockLoggingHandler
    override def auditSource: String = "the-project-name"
  }

  "creating a DefaultAuditConnector" should {
    "allow the configuration to be specified" in {
      val testPort = 9876
      val connector = new DefaultAuditConnector(AuditingConfig(
        consumer = Some(Consumer(BaseUri("localhost", testPort, "http"))),
        enabled = true,
        auditSource = "the-project-name")
      )

      val dataCall = DataCall(Map(), Map(), DateTime.now())

      val wireMock = new WireMockServer(testPort)
      WireMock.configureFor("localhost", testPort)
      wireMock.start()

      WireMock.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/write/audit"))
          .withRequestBody(WireMock.containing("DATA_EVENT"))
          .willReturn(WireMock.aResponse().withStatus(204)))

      WireMock.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/write/audit/merged"))
          .withRequestBody(WireMock.containing("MERGED_DATA_EVENT"))
          .willReturn(WireMock.aResponse().withStatus(204)))

      connector.sendEvent(DataEvent("test", "DATA_EVENT")).futureValue
      WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/write/audit")))
      WireMock.reset()

      connector.sendMergedEvent(MergedDataEvent("test", "MERGED_DATA_EVENT", request = dataCall, response = dataCall)).futureValue
      WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/write/audit")))

      wireMock.stop()
    }

    "return Disabled if auditing is not enabled" in {

      val auditConnector = new DefaultAuditConnector(AuditingConfig(
        consumer = None,
        enabled = false,
        auditSource = "the-project-name")
      )

      auditConnector.sendEvent(DataEvent("test", "test"))(HeaderCarrier(), RunInlineExecutionContext).futureValue must be(AuditResult.Disabled)
    }

  }

  "sendMergedEvent" should {
    "call sendEvent with event converted to json" in {
      when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)

      val mergedEvent = MergedDataEvent("Test", "Test", "TestEventId",
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)),
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)))

      testAuditConnector.sendMergedEvent(mergedEvent).futureValue mustBe Success

      verify(mockAuditHandler).sendEvent(anyString())(any[HeaderCarrier])
      verifyZeroInteractions(mockLoggingHandler)
    }
  }

  "sendEvent" should {
    val event = DataEvent("source", "type")

    "call sendEvent with the event converted to json" in {
      when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)

      testAuditConnector.sendEvent(event).futureValue mustBe AuditResult.Success

      verify(mockAuditHandler).sendEvent(anyString())(any[HeaderCarrier])
      verifyZeroInteractions(mockLoggingHandler)
    }

    "add tags if not specified" in {
      when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")))

      testAuditConnector.sendEvent(event)(headerCarrier, global).futureValue mustBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[String])
      verify(mockAuditHandler).sendEvent(captor.capture())(any[HeaderCarrier])
      val tags = (Json.parse(captor.getValue) \ "tags").as[JsObject]
      (tags \ "X-Session-ID").as[String] mustBe "session-123"
    }

  }

  "sendExtendedEvent" should {
    "call sendEvent with extended event data converted to json" in {
      when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)

      testAuditConnector.sendExtendedEvent(event).futureValue mustBe AuditResult.Success

      verify(mockAuditHandler).sendEvent(anyString())(any[HeaderCarrier])
      verifyZeroInteractions(mockLoggingHandler)
    }

    "sendExplicitEvent Map[String,String]" should {
      "call Datastream with tags read from headerCarrier" in {
        when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        testAuditConnector.sendExplicitAudit("theAuditType", Map("a" -> "1"))(headerCarrier, RunInlineExecutionContext)

        val captor = ArgumentCaptor.forClass(classOf[String])
        verify(mockAuditHandler).sendEvent(captor.capture())(any[HeaderCarrier])
        (Json.parse(captor.getValue) \ "auditSource").as[String] mustBe "the-project-name"
        val tags = (Json.parse(captor.getValue) \ "tags").as[JsObject]
        (tags \ "X-Session-ID").as[String] mustBe "session-123"
        (tags \ "path").as[String] mustBe "/a/b/c"
        (Json.parse(captor.getValue) \ "detail").as[Map[String,String]] mustBe Map("a" -> "1")
      }
    }

    "sendExplicitEvent [T]" should {
      "call Datastream with tags read from headerCarrier and serialize T" in {
        when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)

        val writes = Json.writes[MyExampleAudit]

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        testAuditConnector.sendExplicitAudit("theAuditType", MyExampleAudit("Agent","123"))(headerCarrier, RunInlineExecutionContext, writes)

        val captor = ArgumentCaptor.forClass(classOf[String])
        verify(mockAuditHandler).sendEvent(captor.capture())(any[HeaderCarrier])
        (Json.parse(captor.getValue) \ "auditSource").as[String] mustBe "the-project-name"
        val tags = (Json.parse(captor.getValue) \ "tags").as[JsObject]
        (tags \ "X-Session-ID").as[String] mustBe "session-123"
        (tags \ "path").as[String] mustBe "/a/b/c"
        val detail = (Json.parse(captor.getValue) \ "detail").as[JsObject]
        (detail \ "userType").as[String] mustBe "Agent"
        (detail \ "vrn").as[String] mustBe "123"
      }
    }
  }

  "sendExplicitEvent JsObject" should {
    "call Datastream with tags read from headerCarrier and pass through detail" in {
      when(mockAuditHandler.sendEvent(anyString())(any[HeaderCarrier])).thenReturn(HandlerResult.Success)

      val expectedDetail = Json.obj("Address" -> Json.obj("line1" -> "Road", "postCode" -> "123"))
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
      testAuditConnector.sendExplicitAudit("theAuditType", expectedDetail)(headerCarrier, RunInlineExecutionContext)

      val captor = ArgumentCaptor.forClass(classOf[String])
      verify(mockAuditHandler).sendEvent(captor.capture())(any[HeaderCarrier])
      (Json.parse(captor.getValue) \ "auditSource").as[String] mustBe "the-project-name"
      val tags = (Json.parse(captor.getValue) \ "tags").as[JsObject]
      (tags \ "X-Session-ID").as[String] mustBe "session-123"
      (tags \ "path").as[String] mustBe "/a/b/c"
      val detail = (Json.parse(captor.getValue) \ "detail").as[JsObject]
      detail mustBe expectedDetail
    }
  }

}

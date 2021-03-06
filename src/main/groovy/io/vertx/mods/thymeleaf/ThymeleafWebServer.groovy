/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.mods.thymeleaf

import org.vertx.mods.web.StaticFileHandler
import org.vertx.mods.web.WebServerBase

import java.util.HashMap

import groovy.transform.CompileStatic

import org.vertx.java.core.AsyncResult
import org.vertx.java.core.Future
import org.vertx.java.core.Handler
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.http.HttpServerRequest
import org.vertx.java.core.http.RouteMatcher
import org.vertx.java.core.json.JsonObject


/**
 * @author pidster
 *
 */
@CompileStatic
class ThymeleafWebServer extends WebServerBase {

  String regex
  String match
  String preParser
  String parser

  @Override
  public void start(Future<Void> result) {
    super.start()

    this.regex = getMandatoryStringConfig('regex')
    this.match = getOptionalStringConfig('match', '$1')
    this.preParser = getOptionalStringConfig('pre-parser', null)
    this.parser = getOptionalStringConfig('parser', ThymeleafTemplateParser.DEFAULT_ADDRESS)
    this.config.putBoolean('route_matcher', true)

    container.deployWorkerVerticle('groovy:io.vertx.mods.thymeleaf.ThymeleafTemplateParser', config, 1, false)

    // NB can't use Java's super. inside a Closure
    super.start(result)
  }

  @Override
  protected RouteMatcher routeMatcher() {

    def rm = new RouteMatcher()
    rm.allWithRegEx(regex, { HttpServerRequest req->

      JsonObject msg = new JsonObject()
      msg.putString('templateName', req.path().replaceAll(regex, match))
      msg.putString('method', req.method())
      msg.putString('path', req.path())
      msg.putString('query', req.query())
      msg.putString('uri', req.uri())
      msg.putObject('params', new JsonObject(new HashMap<String,Object>(req.params())))
      msg.putObject('headers', new JsonObject(new HashMap<String,Object>(req.headers())))

      if (!!this.preParser) {
        sendToPreParserFirst(req, msg)
      }
      else {
        handleRequest(req, msg)
      }

    } as Handler)

    // send all non-matches to the webserver
    rm.noMatch(staticHandler())

    return rm
  }

  private final void sendToPreParserFirst(HttpServerRequest req, JsonObject msg) {
    vertx.eventBus().send(preParser, msg, { Message event->
      def reply = event.body() as JsonObject
      handleRequest(req, reply)
    } as Handler)
  }

  private final void handleRequest(HttpServerRequest req, JsonObject msg) {
    def internal = this.&sendToParserAndReply
    def callback = internal.curry(req)
    getRequestParameters(msg, callback)
  }

  private final void sendToParserAndReply(HttpServerRequest req, JsonObject msg) {
    vertx.eventBus().send(parser, msg, { Message event->

      def body = ((JsonObject) event.body()).toMap()
      int statusCode = body['status'] as int
      String chunk = body['rendered']
      req.response().setStatusCode statusCode
      req.response().end(chunk)

    } as Handler)
  }

  /**
   * Extendable method for customising the data sent to the template
   * parser
   * 
   * @param msg
   * @param callback
   */
  protected void getRequestParameters(JsonObject msg, Closure callback) {
    callback.call(msg)
  }

}

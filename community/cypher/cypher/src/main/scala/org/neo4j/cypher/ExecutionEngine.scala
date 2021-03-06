/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher

import org.neo4j.cypher.internal._
import org.neo4j.kernel.{GraphDatabaseAPI, InternalAbstractGraphDatabase}
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.kernel.impl.util.StringLogger
import scala.collection.JavaConverters._
import java.util.{Map => JavaMap}
import org.neo4j.cypher.internal.compiler.v2_1.prettifier.Prettifier
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge
import org.neo4j.graphdb.config.Setting
import org.neo4j.kernel.api.Statement
import org.neo4j.cypher.internal.TransactionInfo
import org.neo4j.cypher.internal.compiler.v2_1.Monitors

class ExecutionEngine(graph: GraphDatabaseService, logger: StringLogger = StringLogger.DEV_NULL) {

  require(graph != null, "Can't work with a null graph database")

  // true means we run inside REST server
  protected val isServer = false
  protected val graphAPI = graph.asInstanceOf[GraphDatabaseAPI]
  protected val monitors = Monitors(
    graphAPI.getDependencyResolver.resolveDependency(classOf[org.neo4j.kernel.monitoring.Monitors])
  )
  protected val compiler = createCompiler()

  @throws(classOf[SyntaxException])
  def profile(query: String): ExecutionResult = profile(query, Map[String, Any]())
  @throws(classOf[SyntaxException])
  def profile(query: String, params: JavaMap[String, Any]): ExecutionResult = profile(query, params.asScala.toMap)

  @throws(classOf[SyntaxException])
  def profile(query: String, params: Map[String, Any]): ExecutionResult = {
    logger.debug(query)
    val (plan, extractedParams, txInfo) = prepare(query)
    plan.profile(graphAPI, txInfo, params ++ extractedParams)
  }

  @throws(classOf[SyntaxException])
  def execute(query: String): ExecutionResult = execute(query, Map[String, Any]())

  @throws(classOf[SyntaxException])
  def execute(query: String, params: JavaMap[String, Any]): ExecutionResult = execute(query, params.asScala.toMap)

  @throws(classOf[SyntaxException])
  def execute(query: String, params: Map[String, Any]): ExecutionResult = {
    logger.debug(query)
    val (plan, extractedParams, txInfo) = prepare(query)
    plan.execute(graphAPI, txInfo, params ++ extractedParams)
  }

  @throws(classOf[SyntaxException])
  private def prepare(query: String): (ExecutionPlan, Map[String, Any], TransactionInfo) = {
    var n = 0
    while (n < ExecutionEngine.PLAN_BUILDING_TRIES) {
      // create transaction and query context
      var touched = false
      val isTopLevelTx = !txBridge.hasTransaction
      val tx = graph.beginTx()
      val statement = txBridge.instance()
      val (plan, extractedParameters) = try {
        // fetch plan cache
        val planCache = getOrCreateFromSchemaState(statement, {
          new LRUCache[String, (ExecutionPlan, Map[String, Any])](getPlanCacheSize)
        })

        // get plan or build it
        planCache.getOrElseUpdate(query, {
          touched = true
          compiler.prepare(query, graph, statement)
        })
      }
      catch {
        case (t: Throwable) =>
          statement.close()
          tx.failure()
          tx.close()
          throw t
      }

      if (touched) {
        statement.close()
        tx.success()
        tx.close()
      }
      else {
        // close the old statement reference after the statement has been "upgraded"
        // to either a schema data or a schema statement, so that the locks are "handed over".
        statement.close()
        return (plan, extractedParameters, TransactionInfo(tx, isTopLevelTx, txBridge.instance()))
      }

      n += 1
    }

    throw new IllegalStateException("Could not execute query due to insanely frequent schema changes")
  }

  private val txBridge = graph.asInstanceOf[GraphDatabaseAPI]
    .getDependencyResolver
    .resolveDependency(classOf[ThreadToStatementContextBridge])

  private def getOrCreateFromSchemaState[V](statement: Statement, creator: => V) = {
    val javaCreator = new org.neo4j.helpers.Function[ExecutionEngine, V]() {
      def apply(key: ExecutionEngine) = creator
    }
    statement.readOperations().schemaStateGetOrCreate(this, javaCreator)
  }

  def prettify(query: String): String = Prettifier(query)

  private def createCompiler(): CypherCompiler = {
    val version = optGraphSetting[String](
      graph, GraphDatabaseSettings.cypher_parser_version, CypherVersion.vDefault.name
    )
    new CypherCompiler(graph, monitors, CypherVersion(version))
  }

  private def getPlanCacheSize: Int =
    optGraphSetting[java.lang.Integer](
      graph, GraphDatabaseSettings.query_cache_size, ExecutionEngine.DEFAULT_PLAN_CACHE_SIZE
    )

  private def optGraphSetting[V](graph: GraphDatabaseService, setting: Setting[V], defaultValue: V): V = {
    def optGraphAs[T <: GraphDatabaseService : Manifest]: PartialFunction[GraphDatabaseService, T] = {
      case (db: T) => db
    }

    optGraphAs[InternalAbstractGraphDatabase]
      .andThen(g => Option(g.getConfig.get(setting)))
      .andThen(_.getOrElse(defaultValue))
      .applyOrElse(graph, (_: GraphDatabaseService) => defaultValue)
  }
}

object ExecutionEngine {
  val DEFAULT_PLAN_CACHE_SIZE: Int = 100
  val PLAN_BUILDING_TRIES: Int = 20
}



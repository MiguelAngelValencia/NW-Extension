package org.nlogo.extensions.nw

import org.nlogo.api.Turtle
import org.nlogo.api.Agent
import org.nlogo.api.AgentSet
import org.nlogo.api.Argument
import org.nlogo.api.Context
import org.nlogo.api.DefaultClassManager
import org.nlogo.api.DefaultCommand
import org.nlogo.api.DefaultReporter
import org.nlogo.api.ExtensionException
import org.nlogo.api.I18N
import org.nlogo.api.Link
import org.nlogo.api.LogoList
import org.nlogo.api.PrimitiveManager
import org.nlogo.api.Syntax
import org.nlogo.extensions.nw.NetworkExtensionUtil.AgentSetToNetLogoAgentSet
import org.nlogo.extensions.nw.NetworkExtensionUtil.AgentToNetLogoAgent
import org.nlogo.extensions.nw.NetworkExtensionUtil.EnrichArgument
import org.nlogo.extensions.nw.NetworkExtensionUtil.TurtleToNetLogoTurtle
import org.nlogo.api.ScalaConversions._
import edu.uci.ics.jung.algorithms.cluster.BicomponentClusterer

class NetworkExtension extends DefaultClassManager {
  override def load(primManager: PrimitiveManager) {
    primManager.addPrimitive("link-distance", LinkDistance)
    primManager.addPrimitive("link-path", LinkPath)
    primManager.addPrimitive("snapshot", Snapshot)
    primManager.addPrimitive("betweenness-centrality", BetweennessCentralityPrim)
    primManager.addPrimitive("normalized-betweenness-centrality", NormalizedBetweennessCentralityPrim)
    primManager.addPrimitive("random-walk-betweenness", RandomWalkBetweennessPrim)
    primManager.addPrimitive("normalized-random-walk-betweenness", NormalizedRandomWalkBetweennessPrim)
    primManager.addPrimitive("k-means-clusters", KMeansClusters)
    primManager.addPrimitive("bicomponent-clusters",BicomponentClusters)
  }
}

object NetworkExtensionUtil {
  implicit def AgentSetToNetLogoAgentSet(agentSet: AgentSet) =
    agentSet.asInstanceOf[org.nlogo.agent.AgentSet]
  implicit def AgentToNetLogoAgent(agent: Agent) =
    agent.asInstanceOf[org.nlogo.agent.Agent]
  implicit def TurtleToNetLogoTurtle(turtle: Turtle) =
    turtle.asInstanceOf[org.nlogo.agent.Turtle]
  implicit def LinkToNetLogoLink(link: Link) =
    link.asInstanceOf[org.nlogo.agent.Link]
  implicit def AgentToRichAgent(agent: Agent) = new RichAgent(agent)
  class RichAgent(agent: Agent) {
    def requireAlive =
      if (agent.id != -1) // is alive
        agent
      else throw new ExtensionException(
        I18N.errors.get("org.nlogo.$common.thatAgentIsDead"))
  }
  implicit def AgentSetToRichAgentSet(agentSet: AgentSet) = new RichAgentSet(agentSet)
  class RichAgentSet(agentSet: AgentSet) {
    def isLinkSet = classOf[Link].isAssignableFrom(agentSet.`type`)
    def isTurtleSet = classOf[Turtle].isAssignableFrom(agentSet.`type`)
    def isLinkBreed = {
      val w = agentSet.world.asInstanceOf[org.nlogo.agent.World]
      (agentSet eq w.links) || w.isLinkBreed(agentSet)
    }
    def requireTurtleSet =
      if (isTurtleSet) agentSet
      else throw new ExtensionException("Expected input to be a turtleset")
    def requireLinkSet =
      if (isLinkSet) agentSet
      else throw new ExtensionException("Expected input to be a linkset")
    def requireLinkBreed =
      if (isLinkBreed) agentSet
      else throw new ExtensionException(
        I18N.errors.get("org.nlogo.prim.etc.$common.expectedLastInputToBeLinkBreed"))
  }
  implicit def EnrichArgument(arg: Argument) = new RichArgument(arg)
  class RichArgument(arg: Argument) {
    def getStaticGraph = arg.get match {
      case g: StaticNetLogoGraph => g
      case _ => throw new ExtensionException(
        "Expected input to be a network snapshot")
    }
    def getGraph = arg.get match {
      case as: AgentSet          => new LiveNetLogoGraph(as.requireLinkBreed)
      case g: StaticNetLogoGraph => g
      case _ => throw new ExtensionException(
        "Expected input to be either a linkset or a network snapshot")
    }
  }
}

object Snapshot extends DefaultReporter {
  override def getSyntax =
    Syntax.reporterSyntax(
      Array(Syntax.TurtlesetType, Syntax.LinksetType),
      Syntax.WildcardType,
      agentClassString = "OTPL")
  override def report(args: Array[Argument], context: Context): AnyRef = {
    val turtleSet = args(0).getAgentSet
    val linkSet = args(1).getAgentSet
    new StaticNetLogoGraph(linkSet, turtleSet).toLogoObject // make extension type
  }
}

object KMeansClusters extends DefaultReporter {
  override def getSyntax = Syntax.reporterSyntax(
    Array(Syntax.WildcardType, Syntax.NumberType, Syntax.NumberType, Syntax.NumberType),
    Syntax.ListType,
    agentClassString = "OTPL")
  override def report(args: Array[Argument], context: Context) = {
    args(0).getStaticGraph.asJungGraph
      .kMeansClusterer
      .clusters(
        nbClusters = args(1).getIntValue,
        maxIterations = args(2).getIntValue,
        convergenceThreshold = args(3).getDoubleValue)
      .toLogoList
  }
}

object BicomponentClusters extends DefaultReporter {
  override def getSyntax = Syntax.reporterSyntax(
    Array(Syntax.WildcardType),
    Syntax.ListType,
    agentClassString = "OTPL")
  override def report(args: Array[Argument], context: Context) = {
    args(0).getStaticGraph.asUndirectedJungGraph
      .bicomponentClusterer
      .clusters
      .toLogoList
  }
}

trait JungScorerPrim extends DefaultReporter {
  override def getSyntax = Syntax.reporterSyntax(
    Array(Syntax.WildcardType),
    Syntax.NumberType,
    agentClassString = "-T-L")
  type G <: JungGraph
  def asGraph(g: StaticNetLogoGraph): G
  def score(agent: Agent, g: G): Double
  override def report(args: Array[Argument], context: Context): AnyRef =
    score(context.getAgent, asGraph(args(0).getStaticGraph)).toLogoObject
}

trait UntypedJungScorerPrim extends JungScorerPrim {
  type G = UntypedJungGraph
  def asGraph(g: StaticNetLogoGraph) = g.asJungGraph
}

trait UndirectedJungScorerPrim extends JungScorerPrim {
  type G = UndirectedJungGraph
  def asGraph(g: StaticNetLogoGraph) = g.asUndirectedJungGraph
}

object BetweennessCentralityPrim extends UntypedJungScorerPrim {
  override def score(agent: Agent, graph: G) = graph.betweennessCentrality.get(agent)
}

object NormalizedBetweennessCentralityPrim extends UntypedJungScorerPrim {
  override def score(agent: Agent, graph: G) = graph.betweennessCentrality.getNormalized(agent)
}

object RandomWalkBetweennessPrim extends UndirectedJungScorerPrim {
  override def score(agent: Agent, graph: G) = graph.randomWalkBetweenness.get(agent)
}

object NormalizedRandomWalkBetweennessPrim extends UndirectedJungScorerPrim {
  override def score(agent: Agent, graph: G) = graph.randomWalkBetweenness.getNormalized(agent)
}

object LinkPath extends DefaultReporter {
  override def getSyntax = Syntax.reporterSyntax(
    Array(Syntax.TurtleType, Syntax.LinksetType | Syntax.WildcardType),
    Syntax.ListType,
    agentClassString = "-T--")
  override def report(args: Array[Argument], context: Context): AnyRef = {
    val start = context.getAgent.asInstanceOf[Turtle]
    val end = args(0).getAgent.asInstanceOf[Turtle]
    val path = args(1).getGraph.asJungGraph.dijkstraShortestPath.getPath(start, end)
    LogoList.fromJava(path)
  }
}

object LinkDistance extends DefaultReporter {
  override def getSyntax = Syntax.reporterSyntax(
    Array(Syntax.TurtleType, Syntax.LinksetType | Syntax.WildcardType),
    Syntax.NumberType | Syntax.BooleanType,
    agentClassString = "-T--")
  override def report(args: Array[Argument], context: Context): AnyRef = {
    val start = context.getAgent.asInstanceOf[Turtle]
    val end = args(0).getAgent.asInstanceOf[Turtle]
    val path = args(1).getGraph.asJungGraph.dijkstraShortestPath.getPath(start, end)
    Option(path.size).filterNot(0==).getOrElse(false).toLogoObject
  }
}
package com.intel.graphs

import scala.collection.immutable.IntMap

import scalaz.{Node => _, _}
import scalaz.syntax.std.map._
import scalaz.syntax.monoid._
import scalaz.std.vector._

/** A module of graphs, parameterized on the type of the `Node` unique identifier */
class Graphs[Node] {

  /** The view of a graph focused on the context surrounding a particular node. */
  case class Context[A,B](inEdges: Adj[B], vertex: Node, label: A, outEdges: Adj[B]) {

    /** All the incoming edges plus identity arrows to self */
    def ins: Adj[B] = inEdges ++ outEdges.filter(_._2 == vertex)

    /** All the outgoing edges plus identity arrows from self */
    def outs: Adj[B] = outEdges ++ inEdges.filter(_._2 == vertex)

    /** All the targets of outgoing edges */
    def successors: Vector[Node] = outs.map(_._2)

    /** All the sources of incoming edges */
    def predecessors: Vector[Node] = ins.map(_._2)

    /** All neighbors of the node */
    def neighbors: Vector[Node] = (inEdges ++ outEdges).map(_._2)

    def toGrContext: GrContext[A,B] = GrContext(fromAdj(inEdges), label, fromAdj(outEdges))

    /** Insert a successor after the focused node */
    def addSucc(n: Node, edge: B) = Context(inEdges, vertex, label, outEdges :+ (edge -> n))

    /** Insert a predecessor after the focused node */
    def addPred(n: Node, edge: B) = Context((edge -> n) +: inEdges, vertex, label, outEdges)
  }

  /** Unlabeled Edge */
  case class Edge(from: Node, to: Node)

  /** Labeled Edge */
  case class LEdge[A](from: Node, to: Node, label: A) {
    def map[B](f: A => B): LEdge[B] = LEdge(from, to, f(label))
    def edge: Edge = Edge(from, to)
  }

  /** Labeled Node */
  case class LNode[A](vertex: Node, label: A) {
    def map[B](f: A => B): LNode[B] = LNode(vertex, f(label))
  }

  /** The label, predecessors, and successors of a given node */
  case class GrContext[A,B](inEdges: Map[Node, Vector[B]],
                            label: A,
                            outEdges: Map[Node, Vector[B]]) {
    def toContext(v: Node): Context[A,B] = Context(toAdj(inEdges), v, label, toAdj(outEdges))
  }

  /**
   * The decomposition of a graph into a detached context focused on one node
   * and the rest of the graph.
   */
  case class Decomp[A,B](ctx: Option[Context[A,B]], rest: Graph[A,B]) {
    def addSucc(node: LNode[A], edge: B): Decomp[A,B] =
      ctx.map(x => GDecomp(x, rest).addSucc(node, edge).toDecomp).getOrElse(this)
    def addPred(node: LNode[A], edge: B): Decomp[A,B] =
      ctx.map(x => GDecomp(x, rest).addPred(node, edge).toDecomp).getOrElse(this)
    def toGraph: Graph[A,B] = ctx.foldLeft(rest)(_ & _)
  }

  /** The same as `Decomp`, only more sure of itself */
  case class GDecomp[A,B](ctx: Context[A, B], rest: Graph[A, B]) {
    def addSucc(node: LNode[A], edge: B): GDecomp[A,B] =
      GDecomp(Context(Vector(edge -> ctx.vertex), node.vertex, node.label, Vector()), rest & ctx)
    def addPred(node: LNode[A], edge: B): GDecomp[A,B] =
      GDecomp(Context(Vector(), node.vertex, node.label, Vector(edge -> ctx.vertex)), rest & ctx)
    def toDecomp: Decomp[A,B] = Decomp(Some(ctx), rest)
    def toGraph: Graph[A,B] = rest & ctx
  }

  /** The same as `Decomp`, but possibly with multiple detached foci */
  case class MultiDecomp[A,B](ctxs: Vector[Context[A,B]], rest: Graph[A,B])

  /** The internal representation of a graph */
  type GraphRep[A,B] = Map[Node, GrContext[A,B]]

  /** Quasi-unlabeled node */
  type UNode = LNode[Unit]

  /** Quasi-unlabaled edge */
  type UEdge = LEdge[Unit]

  /** Labeled links to or from a node */
  type Adj[B] = Vector[(B, Node)]

  /**
   * The decomposition of a graph into two detached
   * contexts focused on distinguished "first" and "last" nodes.
   */
  case class BiDecomp[A,B](first: Context[A,B], last: Context[A,B], rest: Graph[A,B]) {
    def toGraph: Graph[A,B] = rest & first & last

    /** Appends a successor to the last node in this graph and makes that the last node. */
    def addSucc(node: LNode[A], edge: B): BiDecomp[A,B] =
      BiDecomp(first, Context(Vector(edge -> last.vertex), node.vertex, node.label, Vector()), rest & last)

    /** Prepends a predecessor to the first node in this graph and makes that the first node. */
    def addPred(node: LNode[A], edge: B): BiDecomp[A,B] =
      BiDecomp(Context(Vector(edge -> first.vertex), node.vertex, node.label, Vector()), last, rest & first)

    /**
     * Appends one decomposition to another. The first node of this graph will be the first node of the result.
     * The last node of the given graph will be the last node of the result. The given edge will be added
     * from the last node of this graph to the first node of the given graph.
     */
    def append(b: BiDecomp[A,B], edge: B): BiDecomp[A,B] =
      BiDecomp(first, b.last, rest union b.rest & last & b.first addEdge LEdge(last.vertex, b.first.vertex, edge))
  }

  implicit def nodeOrder[A](implicit N: Order[Node], A: Order[A]): Order[LNode[A]] =
    Order.order { (a, b) =>
      N.order(a.vertex, b.vertex) |+| A.order(a.label, b.label)
    }

  implicit def ledgeOrder[A](implicit N: Order[Node], A: Order[A]): Order[LEdge[A]] =
    Order.order { (a, b) =>
      N.order(a.from, b.from) |+| N.order(a.to, b.to) |+| A.order(a.label, b.label)
    }

  implicit def edgeOrder[A](implicit N: Order[Node]): Order[Edge] =
    Order.order { (a, b) =>
      N.order(a.from, b.from) |+| N.order(a.to, b.to)
    }

  implicit def graphOrder[A,B](implicit N: Order[Node], A: Order[A], B: Order[B]): Order[Graph[A, B]] =
    Order.order { (a, b) =>
      implicit val L = Order[LNode[A]].toScalaOrdering
      implicit val E = Order[LEdge[B]].toScalaOrdering
      Order[Vector[LNode[A]]].order(a.labNodes.sorted, b.labNodes.sorted) |+|
      Order[Vector[LEdge[B]]].order(a.labEdges.sorted, b.labEdges.sorted)
    }

  /** An empty graph */
  def empty[A,B]: Graph[A,B] = Graph(Map.empty[Node, GrContext[A,B]])

  /** Create a graph from lists of labeled nodes and edges */
  def mkGraph[A,B](vs: Seq[LNode[A]], es: Seq[LEdge[B]]): Graph[A,B] =
    empty.addNodes(vs).addEdges(es)

  def safeMkGraph[A,B](vs: Seq[LNode[A]], es: Seq[LEdge[B]]): Graph[A,B] =
    empty.addNodes(vs).safeAddEdges(es)

  /** Build a graph from a list of contexts */
  def buildGraph[A,B](ctxs: Seq[Context[A,B]]): Graph[A,B] =
    ctxs.foldLeft(empty[A,B])(_ & _)

  def clear[A,B](g: GraphRep[A,B], v: Node, ns: Vector[Node],
                 f: GrContext[A,B] => GrContext[A,B]): GraphRep[A,B] =
    if (ns.isEmpty) g else clear(g.alter(ns.head)(_ map f), v, ns.tail, f)

  def addSucc[A,B](g: GraphRep[A, B], v: Node, lps: Vector[(B, Node)]): GraphRep[A,B] =
    if (lps.isEmpty) g else addSucc(g.alter(lps.head._2)(_ map { (x: GrContext[A,B]) => x match {
      case GrContext(ps, lp, ss) =>
        GrContext(ps, lp, ss.insertWith(v, Vector(lps.head._1))(_ ++ _))
    }}), v, lps.tail)

  def addPred[A,B](g: GraphRep[A, B], v: Node, lss: Vector[(B, Node)]): GraphRep[A,B] =
    if (lss.isEmpty) g else addPred(g.alter(lss.head._2)(_ map { (x: GrContext[A,B]) => x match {
      case GrContext(ps, lp, ss) =>
        GrContext(ps.insertWith(v, Vector(lss.head._1))(_ ++ _), lp, ss)
    }}), v, lss.tail)

  def clearPred[A,B](g: GraphRep[A,B], v: Node, ns: Vector[Node]): GraphRep[A,B] =
    clear(g, v, ns, { case GrContext(ps, l, ss) => GrContext(ps - v, l, ss) })

  def clearSucc[A,B](g: GraphRep[A,B], v: Node, ns: Vector[Node]): GraphRep[A,B] =
    clear(g, v, ns, { case GrContext(ps, l, ss) => GrContext(ps, l, ss - v) })

  /** Turn an intmap of vectors of labels into an adjacency list of labeled edges */
  def toAdj[B](bs: Map[Node, Vector[B]]): Adj[B] = bs.toVector.flatMap {
    case (n, ls) => ls.map(m => (m, n))
  }

  /** Turn an adjacency list of labeled edges into an intmap of vectors of labels */
  def fromAdj[B](adj: Adj[B]): Map[Node, Vector[B]] =
    adj.foldLeft(Map.empty[Node, Vector[B]]) {
      case (m, (b, n)) => m + (n -> (m.get(n).toVector.flatten :+ b))
    }

  /**
   * An implementation of an inductive graph using `Map`.
   * Nodes are labeled with `A`, and edges are labeled with `B`.
   */
  case class Graph[A,B](rep: GraphRep[A,B]) {
    def isEmpty = rep.isEmpty

    /**
     * Returns a context focused on the given node, if present,
     * and the graph with that node removed.
     */
    def decomp(n: Node): Decomp[A, B] = rep.get(n) match {
      case None => Decomp(None, this)
      case Some(GrContext(p, label, s)) =>
        val g1 = rep - n
        val pp = p - n
        val sp = s - n
        val g2 = clearPred(g1, n, sp.keys.toVector)
        val g3 = clearSucc(g2, n, pp.keys.toVector)
        Decomp(Some(Context(toAdj(pp), n, label, toAdj(s))), Graph(g3))
    }

    def bidecomp(first: Node, last: Node): Option[BiDecomp[A,B]] = {
      val Decomp(c1, r1) = decomp(first)
      val Decomp(c2, _) = decomp(last)
      for {
        x <- c1
        y <- c2
      } yield BiDecomp(x, y, r1.decomp(y.vertex).rest)
    }

    /**
     * Merge the given context into the graph. The context consists of a vertex, its label,
     * its successors, and its predecessors.
     */
    def &(ctx: Context[A,B]): Graph[A,B] = {
      val Context(p, v, l, s) = ctx
      val g1 = rep + (v -> GrContext(fromAdj(p), l, fromAdj(s)))
      val g2 = addSucc(g1, v, p)
      val g3 = addPred(g2, v, s)
      Graph(g3)
    }

    /**
     * Add a node to this graph. If this node already exists with a different label,
     * its label will be replaced with this new one.
     */
    def addNode(n: LNode[A]): Graph[A,B] = {
      val LNode(v, l) = n
      val Decomp(ctx, g) = decomp(v)
      g & ctx.map {
        case x:Context[A,B] => x.copy(label = l)
      }.getOrElse(Context(Vector(), v, l, Vector()))
    }

    /**
     * Add an edge to this graph.
     * Throws an error if the source and target nodes don't exist in the graph.
     */
    def addEdge(e: LEdge[B]): Graph[A,B] =
      safeAddEdge(e,
        sys.error(s"Can't add edge $e since the source and target nodes don't both exist in the graph."))

    /**
     * Add an edge to this graph. If the source and target nodes don't exist in this graph,
     * return the given `failover` graph.
     */
    def safeAddEdge(e: LEdge[B], failover: => Graph[A,B] = this): Graph[A,B] = {
      val LEdge(v, w, l) = e
      val ks = rep.keySet
      if (ks.contains(v) && ks.contains(w)) {
        def addSuccP(p: GrContext[A,B]) = {
          val GrContext(ps, lp, ss) = p
          GrContext(ps, lp, ss.insertWith(w, Vector(l))(_ ++ _))
        }
        def addPredP(p: GrContext[A,B]) = {
          val GrContext(ps, lp, ss) = p
          GrContext(ps.insertWith(v, Vector(l))(_ ++ _), lp, ss)
        }
        val g1 = rep.alter(v)(_ map addSuccP)
        Graph(g1.alter(w)(_ map addPredP))
      } else failover
    }

    /** Add multiple nodes to this graph */
    def addNodes(vs: Seq[LNode[A]]): Graph[A,B] =
      vs.foldLeft(this)(_ addNode _)

    /** Add multiple edges to this graph */
    def addEdges(es: Seq[LEdge[B]]): Graph[A,B] =
      es.foldLeft(this)(_ addEdge _)

    /**
     * Add multiple edges to this graph, ignoring edges whose source and target nodes
     * don't already exist in the graph.
     */
    def safeAddEdges(es: Seq[LEdge[B]]): Graph[A,B] =
      es.foldLeft(this)(_ safeAddEdge _)

    /** Adds all the nodes and edges from one graph to another. */
    def union(g: Graph[A,B]): Graph[A,B] =
      addNodes(g.labNodes).addEdges(g.labEdges)

    /** Remove a node from this graph */
    def removeNode(v: Node): Graph[A,B] =
      removeNodes(Seq(v))

    /** Remove multiple nodes from this graph */
    def removeNodes(vs: Seq[Node]): Graph[A,B] =
      if (vs.isEmpty) this else decomp(vs.head).rest.removeNodes(vs.tail)

    /** Remove an edge from this graph */
    def removeEdge(e: Edge): Graph[A,B] = decomp(e.from) match {
      case Decomp(None, _) => this
      case Decomp(Some(Context(p, v, l, s)), gp) =>
        gp & Context(p, v, l, s.filter { case (_, n) => n != e.to })
    }

    /** Remove an edge from this graph only if the label matches */
    def removeLEdge(e: LEdge[B]): Graph[A,B] = decomp(e.from) match {
      case Decomp(None, _) => this
      case Decomp(Some(Context(p, v, l, s)), gp) =>
        gp & Context(p, v, l, s.filter { case (x, n) => (x != e.label) || (n != e.to) })
    }

    /** Remove multiple edges from this graph */
    def removeEdges(es: Seq[Edge]): Graph[A,B] =
      es.foldLeft(this)(_ removeEdge _)

    /** A list of all the nodes in the graph and their labels */
    def labNodes: Vector[LNode[A]] =
      rep.toVector map { case (node, GrContext(_, label, _)) => LNode(node, label) }

    /** A list of all the nodes in the graph */
    def nodes: Vector[Node] = labNodes.map(_.vertex)

    /**
     * Decompose this graph into the context for an arbitrarily chosen node
     * and the rest of the graph.
     */
    def decompAny: GDecomp[A,B] = labNodes match {
      case Vector() => sys.error("Cannot decompose an empty graph")
      case vs =>
        val Decomp(Some(c), g) = decomp(vs.head.vertex)
        GDecomp(c, g)
    }

    /** The number of nodes in this graph */
    def countNodes: Int = rep.size

    /** A list of all the edges in the graph and their labels */
    def labEdges: Vector[LEdge[B]] = for {
      (node, GrContext(_, _, s)) <- rep.toVector
      (next, labels) <- s.toVector
      label <- labels
    } yield LEdge(node, next, label)

    /** A list of all the edges in the graph */
    def edges = labEdges.map { case LEdge(v, w, _) => Edge(v, w) }

    /** Fold a function over the graph */
    def fold[C](u: C)(f: (Context[A,B], C) => C): C = {
      val GDecomp(c, g) = decompAny
      if (isEmpty) u else f(c, g.fold(u)(f))
    }

    /** Map a function over the graph */
    def gmap[C,D](f: Context[A,B] => Context[C,D]): Graph[C,D] =
      Graph(rep.map { case (k, v) => k -> f(v.toContext(k)).toGrContext })

    /** Map a function over the node labels in the grap */
    def nmap[C](f: A => C): Graph[C, B] =
      Graph(rep.mapValues { case GrContext(ps, a, ss) => GrContext(ps, f(a), ss) })

    /** Map a function over the edge labels in the graph */
    def emap[C](f: B => C): Graph[A, C] =
      Graph(rep.mapValues {
        case GrContext(ps, a, ss) =>
          GrContext(ps mapValues (_ map f), a, ss mapValues (_ map f))
      })

    /** Returns true if the given node is in the graph, otherwise false */
    def contains(v: Node): Boolean = decomp(v) match {
      case Decomp(Some(_), _) => true
      case _ => false
    }

    /**
     * Find the context for the given node. Causes an error if the node is not
     * present in the graph.
     */
    def context(v: Node): Context[A,B] =
      decomp(v).ctx.getOrElse(sys.error(s"Node $v is not present in the graph"))

    /** All the inbound links of the given node, including self-edges */
    def ins(v: Node): Adj[B] =
      context(v).ins

    /** All the outbound links of the given node, including self-edges */
    def outs(v: Node): Adj[B] =
      context(v).outs

    /** Find the label for a node */
    def label(v: Node): Option[A] =
      decomp(v).ctx.map(_.label)

    /** Find the neighbors of a node */
    def neighbors(v: Node): Vector[Node] = {
      val Context(p, _, _, s) = context(v)
      (p ++ s).map(_._2)
    }

    /** Find all nodes that have a link from the given node */
    def successors(v: Node): Vector[Node] =
      outs(v).map(_._2)

    /** Find all nodes that have a link to the given node */
    def predecessors(v: Node): Vector[Node] =
      ins(v).map(_._2)

    /** Find all outbound edges for the given node */
    def outEdges(v: Node): Vector[LEdge[B]] =
      outs(v).map { case (l, w) => LEdge(v, w, l) }

    /** Find all inbound edges for the given node */
    def inEdges(v: Node): Vector[LEdge[B]] =
      ins(v).map { case (l, w) => LEdge(v, w, l) }

    /** The number of outbound edges from the given node */
    def outDegree(v: Node): Int =
      outs(v).length

    /** The number of inbound edges from the given node */
    def inDegree(v: Node): Int =
      ins(v).length

    /** The number of connections to and from the given node */
    def degree(v: Node): Int = {
      val Context(p, _, _, s) = context(v)
      p.length + s.length
    }

    /** Find an edge between two nodes */
    def findEdge(e: Edge): Option[LEdge[B]] =
      labEdges.find(c => c.from == e.from && c.to == e.to)

    /** Replace an edge with a new one */
    def updateEdge(e: LEdge[B]): Graph[A,B] =
      removeEdge(Edge(e.from, e.to)).addEdge(e)

    /** Update multiple edges */
    def updateEdges(es: Seq[LEdge[B]]): Graph[A,B] =
      es.foldLeft(this)(_ updateEdge _)

    /** Replace a node with a new one */
    def updateNode(n: LNode[A]): Graph[A,B] =
      decomp(n.vertex) match {
        case Decomp(Some(Context(p, v, l, s)), rest) =>
          rest & Context(p, n.vertex, n.label, s)
        case _ => this
      }

    /** Update multiple nodes */
    def updateNodes(ns: Seq[LNode[A]]): Graph[A,B] =
      ns.foldLeft(this)(_ updateNode _)

    /** Reverse the direction of all edges */
    def reverse: Graph[A,B] = gmap {
      case Context(p, v, l, s) => Context(s, v, l, p)
    }

    /**
     * Generalized depth-first search.
     */
    final def xdfsWith[C](vs: Seq[Node],
                          d: Context[A, B] => Seq[Node],
                          f: Context[A, B] => C): Vector[C] =
      if (vs.isEmpty || isEmpty) Vector()
      else decomp(vs.head) match {
        case Decomp(Some(c), g) => f(c) +: g.xdfsWith(d(c) ++ vs.tail, d, f)
        case Decomp(None, g) => g.xdfsWith(vs.tail, d, f)
      }

    /**
     * Forward depth-first search.
     */
    def dfsWith[C](vs: Seq[Node], f: Context[A, B] => C): Seq[C] =
      xdfsWith(vs, _.successors, f)

    /** Forward depth-first search. */
    def dfs(vs: Seq[Node]): Seq[Node] = dfsWith(vs, _.vertex)

    /** Undirected depth-first search */
    def udfs(vs: Seq[Node]): Seq[Node] = xdfsWith(vs, _.neighbors, _.vertex)

    /** Reverse depth-first search. Follows predecessors. */
    def rdfs(vs: Seq[Node]): Seq[Node] = xdfsWith(vs, _.predecessors, _.vertex)

    /** Finds the transitive closure of this graph. */
    def tclose: Graph[A,Unit] = {
      val ln = labNodes
      val newEdges = ln.flatMap {
        case LNode(u, _) => reachable(u).map(v => LEdge(u, v, ()))
      }
      empty.addNodes(ln).addEdges(newEdges)
    }

    /** Finds all the reachable nodes from a given node, using DFS */
    def reachable(v: Node): Vector[Node] = dff(Seq(v)).flatMap(_.flatten)

    /**
     * Depth-first forest. Follows successors of the given nodes. The result is
     * a vector of trees of nodes where each path in each tree is a path through the
     * graph.
     */
    def dff(vs: Seq[Node]): Vector[Tree[Node]] = dffWith(vs, _.vertex)

    /**
     * Depth-first forest. Follows successors of the given nodes. The result is
     * a vector of trees of results of passing the context of each node to the function `f`.
     */
    def dffWith[C](vs: Seq[Node], f: Context[A, B] => C): Vector[Tree[C]] =
      xdffWith(vs, _.successors, f)

    /**
     * Generalized depth-first forest. Uses the function `d` to decide which nodes to
     * visit next.
     */
    def xdffWith[C](vs: Seq[Node],
                    d: Context[A, B] => Seq[Node],
                    f: Context[A, B] => C): Vector[Tree[C]] =
      xdfWith(vs, d, f)._1

    /**
     * Generalized depth-first forest. Uses the function `d` to decide which nodes to
     * visit next
     */
    def xdfWith[C](vs: Seq[Node],
                   d: Context[A, B] => Seq[Node],
                   f: Context[A, B] => C): (Vector[Tree[C]], Graph[A, B]) =
      if (vs.isEmpty || isEmpty) (Vector(), this)
      else decomp(vs.head) match {
        case Decomp(None, g) => g.xdfWith(vs.tail, d, f)
        case Decomp(Some(c), g) =>
          val (xs, g2) = g.xdfWith(d(c), d, f)
          val (ys, g3) = g.xdfWith(vs.tail, d, f)
          (Tree.node(f(c), xs.toStream) +: ys, g3)
      }
  }

  implicit def graphMonoid[A,B]: Monoid[Graph[A,B]] = new Monoid[Graph[A,B]] {
    def zero = empty
    def append(g1: Graph[A,B], g2: => Graph[A,B]) = g1 union g2
  }
}

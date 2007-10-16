/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.ipa.slicer;

import java.util.Collection;
import java.util.Collections;
import java.util.Stack;

import com.ibm.wala.dataflow.IFDS.BackwardsSupergraph;
import com.ibm.wala.dataflow.IFDS.IFlowFunctionMap;
import com.ibm.wala.dataflow.IFDS.IMergeFunction;
import com.ibm.wala.dataflow.IFDS.ISupergraph;
import com.ibm.wala.dataflow.IFDS.SolverInterruptedException;
import com.ibm.wala.dataflow.IFDS.TabulationDomain;
import com.ibm.wala.dataflow.IFDS.TabulationProblem;
import com.ibm.wala.dataflow.IFDS.TabulationResult;
import com.ibm.wala.dataflow.IFDS.TabulationSolver;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Collection;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.intset.SparseIntSet;

/**
 * A demand-driven context-sensitive slicer.
 * 
 * This computes a context-sensitive slice, building an SDG and finding
 * realizable paths to a statement using tabulation.
 * 
 * This implementation uses a preliminary pointer analysis to compute data
 * dependence between heap locations in the SDG.
 * 
 * @author sjfink
 * 
 */
public class Slicer {

  public final static boolean DEBUG = false;

  public final static boolean VERBOSE = false;

  /*
   * Experimental option: If BAIL_OUT > 0, then the slicer will stop tabulating
   * when the slice gets bigger than this.
   */
  public final static int BAIL_OUT = -1;

  /**
   * options to control data dependence edges in the SDG
   */
  public static enum DataDependenceOptions {
    FULL("full", false, false, false, false),
    NO_BASE_PTRS("no_base_ptrs", true, false, false, false),
    NO_BASE_NO_HEAP("no_base_no_heap", true, true, false, false),
    NO_HEAP("no_heap", false, true, false, false),
    NONE("none", true, true, true, true),
    REFLECTION("no_base_no_heap_no_cast", true, true, true, true);

    private final String name;

    /**
     * Ignore data dependence edges representing base pointers? e.g for a
     * statement y = x.f, ignore the data dependence edges for x
     */
    private final boolean ignoreBasePtrs;

    /**
     * Ignore all data dependence edges to or from the heap?
     */
    private final boolean ignoreHeap;

    /**
     * Ignore outgoing data dependence edges from a cast statements? [This is a
     * special case option used for reflection processing]
     */
    private final boolean terminateAtCast;

    /**
     * Ignore data dependence manifesting throw exception objects?
     */
    private final boolean ignoreExceptions;

    DataDependenceOptions(String name, boolean ignoreBasePtrs, boolean ignoreHeap, boolean terminateAtCast, boolean ignoreExceptions) {
      this.name = name;
      this.ignoreBasePtrs = ignoreBasePtrs;
      this.ignoreHeap = ignoreHeap;
      this.terminateAtCast = terminateAtCast;
      this.ignoreExceptions = ignoreExceptions;
    }

    public final boolean isIgnoreBasePtrs() {
      return ignoreBasePtrs;
    }

    public final boolean isIgnoreHeap() {
      return ignoreHeap;
    }

    public final boolean isIgnoreExceptions() {
      return ignoreExceptions;
    }

    /**
     * Should data dependence chains terminate at casts? This is used for
     * reflection processing ... we only track flow into casts ... but not out.
     */
    public final boolean isTerminateAtCast() {
      return terminateAtCast;
    }

    public final String getName() {
      return name;
    }
  }

  /**
   * options to control control dependence edges in the sdg
   */
  public static enum ControlDependenceOptions {
    FULL("full"),
    NONE("none"),
    NO_EXCEPTIONAL_EDGES("no_exceptional_edges");

    private final String name;

    ControlDependenceOptions(String name) {
      this.name = name;
    }

    public final String getName() {
      return name;
    }
  }

  /**
   * @param s
   *          a statement of interest
   * @return the backward slice of s.
   */
  public static Collection<Statement> computeBackwardSlice(Statement s, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException {
    return computeSlice(null, Collections.singleton(s), cg, pa, dOptions, cOptions, true);
  }

  /**
   * @param s
   *          a statement of interest
   * @return the forward slice of s.
   */
  public static Collection<Statement> computeForwardSlice(Statement s, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException {
    return computeSlice(null, Collections.singleton(s), cg, pa, dOptions, cOptions, false);
  }

  /**
   * Use the passed-in SDG
   */
  public static Collection<Statement> computeBackwardSlice(SDG sdg, Statement s, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException {
    return computeSlice(sdg, Collections.singleton(s), cg, pa, dOptions, cOptions, true);
  }

  /**
   * Use the passed-in SDG
   */
  public static Collection<Statement> computeForwardSlice(SDG sdg, Statement s, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException {
    return computeSlice(sdg, Collections.singleton(s), cg, pa, dOptions, cOptions, false);
  }

  /**
   * Use the passed-in SDG
   */
  public static Collection<Statement> computeBackwardSlice(SDG sdg, Collection<Statement> ss, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException {
    return computeSlice(sdg, ss, cg, pa, dOptions, cOptions, true);
  }

  /**
   * @param ss
   *          a collection of statements of interest
   */
  protected static Collection<Statement> computeSlice(SDG sdg, Collection<Statement> ss, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions, boolean backward) {
    return computeSlice(sdg, ss, cg, pa, new ModRef(), dOptions, cOptions, backward);
  }

  protected static Collection<Statement> computeSlice(SDG sdg, Collection<Statement> ss, CallGraph cg, PointerAnalysis pa, ModRef modRef,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions, boolean backward) {

    if (VERBOSE) {
      System.err.println("Build SDG...");
    }

    if (sdg == null) {
      sdg = new SDG(cg, pa, modRef, dOptions, cOptions);
    }

    Collection<Statement> rootsConsidered = HashSetFactory.make();
    Stack<Statement> workList = new Stack<Statement>();
    Collection<Statement> result = HashSetFactory.make();
    for(Statement s : ss) {
      workList.push(s);
    }
    while (!workList.isEmpty()) {
      Statement root = workList.pop();
      rootsConsidered.add(root);
      Collection<Statement> empty = Collections.emptySet();
      ISDG sdgView = new SDGView(sdg, empty);
      SliceProblem p = new SliceProblem(root, sdgView, backward);

      if (VERBOSE) {
        System.err.println("worklist now: " + workList.size());
        System.err.println("slice size: " + result.size());
        System.err.println("Tabulate for " + root);
      }

      TabulationSolver<Statement, PDG> solver = TabulationSolver.make(p);
      TabulationResult<Statement, PDG> tr = null;
      try {
        tr = solver.solve();
      } catch (SolverInterruptedException e) {
        e.printStackTrace();
        Assertions.UNREACHABLE();
      }
      if (DEBUG) {
        System.err.println("RESULT");
        System.err.println(tr);
      }
      if (VERBOSE) {
        System.err.println("Tabulated.");
      }
      Collection<Statement> slice = result2Slice(tr);
      result.addAll(slice);

      if (VERBOSE) {
        System.err.println("Compute new roots...");
      }

      Collection<Statement> newRoots = computeNewRoots(slice, root, rootsConsidered, sdg, backward, dOptions);
      for (Statement st : newRoots) {
        workList.push(st);
      }

      if (BAIL_OUT > 0 && result.size() > BAIL_OUT) {
        workList.clear();
        System.err.println("Bailed out at " + result.size());
      }
    }

    if (VERBOSE) {
      System.err.println("Slicer done.");
    }

    return result;
  }

  private static Collection<Statement> computeNewRoots(Collection<Statement> slice, Statement root,
      Collection<Statement> rootsConsidered, ISDG sdg, boolean backward, DataDependenceOptions dOptions) {
    if (backward) {
      return computeNewBackwardRoots(slice, root, rootsConsidered, sdg);
    } else {
      return computeNewForwardRoots(slice, root, rootsConsidered, sdg, dOptions);
    }
  }

  /**
   * TODO: generalize this for any unbalanced parentheses problems
   */
  private static Collection<Statement> computeNewForwardRoots(Collection<Statement> slice, Statement root,
      Collection<Statement> rootsConsidered, ISDG sdg, DataDependenceOptions dOptions) {
    Collection<Statement> result = HashSetFactory.make();

    for (Statement st : slice) {
      if (st.getNode().equals(root.getNode())) {
        switch (st.getKind()) {
        case HEAP_RET_CALLEE:
        case NORMAL_RET_CALLEE:
        case EXC_RET_CALLEE:
          if (Assertions.verifyAssertions && dOptions.isIgnoreExceptions()) {
            Assertions._assert(!st.getKind().equals(Kind.EXC_RET_CALLEE));
          }

          Collection<Statement> succs = Iterator2Collection.toCollection(sdg.getSuccNodes(st));
          succs.removeAll(slice);
          for (Statement s : succs) {
            // s is a statement that is a successor of a return statement to the
            // root
            // method of the slice.
            // normally we expect s to be in the slice ... since it wasn't, it
            // must have been ruled out by balanced parens since we "magically"
            // entered the root method. So, consider s a new "magic root"
            if (!rootsConsidered.contains(s)) {
              if (VERBOSE) {
                System.err.println("Adding root " + s);
              }
              result.add(s);
            }
          }
          break;
        default:
          // do nothing
          break;
        }
      }
    }
    return result;
  }

  /**
   * TODO: generalize this for any unbalanced parentheses problems
   */
  private static Collection<Statement> computeNewBackwardRoots(Collection<Statement> slice, Statement root,
      Collection<Statement> rootsConsidered, ISDG sdg) {
    Collection<Statement> result = HashSetFactory.make();

    for (Statement st : slice) {
      if (st.getNode().equals(root.getNode())) {
        switch (st.getKind()) {
        case HEAP_PARAM_CALLEE:
        case PARAM_CALLEE:
        case METHOD_ENTRY:
          Collection<Statement> preds = Iterator2Collection.toCollection(sdg.getPredNodes(st));
          preds.removeAll(slice);
          for (Statement p : preds) {
            // p is a statement that is a predecessor of an incoming parameter
            // to the root
            // method of the slice.
            // normally we expect p to be in the slice ... since it wasn't, it
            // must have been ruled out by balanced parens since we "magically"
            // entered the root method. So, consider p a new "magic root"
            if (!rootsConsidered.contains(p)) {
              if (VERBOSE) {
                System.err.println("Adding root " + p);
              }
              result.add(p);
            }
          }
          break;
        default:
          // do nothing
          break;
        }
      }
    }
    return result;
  }

  /**
   * @param s
   *          a statement of interest
   * @return the backward slice of s.
   */
  public static Collection<Statement> computeBackwardSlice(Statement s, CallGraph cg, PointerAnalysis pointerAnalysis)
      throws IllegalArgumentException {
    return computeBackwardSlice(s, cg, pointerAnalysis, DataDependenceOptions.FULL, ControlDependenceOptions.FULL);
  }

  /**
   * Convert the results of the tabulation to a slice, represented as a
   * Collection<Statement>
   */
  private static Collection<Statement> result2Slice(final TabulationResult<Statement, PDG> result) {
    return result.getSupergraphNodesReached();
    // final Collection<Statement> nodes = new
    // Iterator2Collection<Statement>(sdg.iterateLazyNodes());
    // Filter f = new Filter() {
    // public boolean accepts(Object o) {
    // Statement st = (Statement) o;
    // if (!nodes.contains(st)) {
    // return false;
    // }
    // SparseIntSet s = result.getResult(st);
    // if ((s != null) && s.contains(0)) {
    // result.getResult((Statement) o);
    // }
    // return s != null && s.contains(0);
    // }
    // };
    // return new Iterator2Collection<Statement>(new
    // FilterIterator<Statement>(nodes.iterator(), f));
  }

  /**
   * Tabulation problem representing slicing
   * 
   */
  private static class SliceProblem implements TabulationProblem<Statement, PDG> {

    private final Statement src;

    private final ISupergraph<Statement, PDG> supergraph;

    private final IFlowFunctionMap<Statement> f;

    public SliceProblem(Statement s, ISDG sdg, boolean backward) {
      this.src = s;
      SDGSupergraph forwards = new SDGSupergraph(sdg, src, backward);
      this.supergraph = backward ? BackwardsSupergraph.make(forwards) : forwards;
      f = new SliceFunctions();
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getDomain()
     */
    public TabulationDomain getDomain() {
      Assertions.UNREACHABLE();
      return null;
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getFunctionMap()
     */
    public IFlowFunctionMap<Statement> getFunctionMap() {
      return f;
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getMergeFunction()
     */
    public IMergeFunction getMergeFunction() {
      return null;
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getReachableOnEntry()
     */
    public SparseIntSet getReachableOnEntry() {
      return SparseIntSet.singleton(0);
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getSupergraph()
     */
    public ISupergraph<Statement, PDG> getSupergraph() {
      return supergraph;
    }
  }

}

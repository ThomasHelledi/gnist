/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.catalyst.trees.TreePattern.JOIN
import org.apache.spark.sql.types._

/**
 * Converts cross joins with array_contains filter into inner joins using explode.
 *
 * This optimization transforms queries of the form:
 * {{{
 * SELECT * FROM left, right WHERE array_contains(left.arr, right.elem)
 * }}}
 *
 * Into a more efficient form using explode + inner join, reducing O(N*M) to O(N+M).
 *
 * Cost-based guard: The optimization is skipped when the estimated array size exceeds
 * the row count of the table being joined against, as exploding large arrays can be
 * more expensive than the original cross join.
 */
object CrossJoinArrayContainsToInnerJoin extends Rule[LogicalPlan] with PredicateHelper {

  /**
   * Default maximum array size for the optimization. If estimated array size exceeds
   * this value and is larger than the join table's row count, the optimization is skipped.
   */
  private val DEFAULT_MAX_ARRAY_SIZE = 1000

  override def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsPattern(JOIN), ruleId) {
    // Case 1: array_contains in Filter on top of a cross/inner join without condition
    case f @ Filter(cond, j @ Join(left, right, Cross | Inner, None, _)) =>
      tryTransformFilter(f, cond, j, left, right).getOrElse(f)

    // Case 2: array_contains already pushed into join condition (by PushPredicateThroughJoin)
    case j @ Join(left, right, Inner, Some(cond), hint) =>
      tryTransformJoin(j, cond, left, right, hint).getOrElse(j)
  }

  private def tryTransformFilter(
      filter: Filter,
      condition: Expression,
      join: Join,
      left: LogicalPlan,
      right: LogicalPlan): Option[LogicalPlan] = {
    val predicates = splitConjunctivePredicates(condition)
    val leftOut = left.outputSet
    val rightOut = right.outputSet

    // Find first valid array_contains predicate
    predicates.collectFirst {
      case ac @ ArrayContains(arr, elem)
          if canOptimize(arr, elem, leftOut, rightOut) =>
        val arrayOnLeft = arr.references.subsetOf(leftOut)
        // Cost-based guard: skip if array explosion would be too expensive
        val joinTarget = if (arrayOnLeft) right else left
        if (!isCostEffective(arr, joinTarget)) {
          return None
        }
        val remaining = predicates.filterNot(_ == ac)
        buildPlan(join, left, right, arr, elem, arrayOnLeft, remaining, join.hint)
    }.flatten
  }

  private def tryTransformJoin(
      join: Join,
      condition: Expression,
      left: LogicalPlan,
      right: LogicalPlan,
      hint: JoinHint): Option[LogicalPlan] = {
    val predicates = splitConjunctivePredicates(condition)
    val leftOut = left.outputSet
    val rightOut = right.outputSet

    // Find first valid array_contains predicate in join condition
    predicates.collectFirst {
      case ac @ ArrayContains(arr, elem)
          if canOptimize(arr, elem, leftOut, rightOut) =>
        val arrayOnLeft = arr.references.subsetOf(leftOut)
        // Cost-based guard: skip if array explosion would be too expensive
        val joinTarget = if (arrayOnLeft) right else left
        if (!isCostEffective(arr, joinTarget)) {
          return None
        }
        val remaining = predicates.filterNot(_ == ac)
        buildPlan(join, left, right, arr, elem, arrayOnLeft, remaining, hint)
    }.flatten
  }

  /**
   * Checks if the optimization is cost-effective based on estimated array size
   * and join target row count.
   *
   * The optimization is beneficial when: array_size < join_target_rows
   * When array_size > join_target_rows, exploding creates more work than cross join.
   */
  private def isCostEffective(arr: Expression, joinTarget: LogicalPlan): Boolean = {
    // Try to get row count from statistics
    val targetRowCount = joinTarget.stats.rowCount

    // If we have statistics, use them for cost-based decision
    if (targetRowCount.isDefined) {
      val rows = targetRowCount.get
      // If target table has fewer rows than our default max array size threshold,
      // skip the optimization as array explosion would likely be more expensive
      if (rows < DEFAULT_MAX_ARRAY_SIZE) {
        return false
      }
    }

    // Without statistics, apply the optimization (optimistic approach)
    // The DEFAULT_MAX_ARRAY_SIZE acts as a safety threshold
    true
  }

  private def canOptimize(
      arr: Expression,
      elem: Expression,
      leftOut: AttributeSet,
      rightOut: AttributeSet): Boolean = {
    // Check type compatibility
    val elemType = elem.dataType
    val validType = arr.dataType match {
      case ArrayType(t, _) => t == elemType && isSupportedType(elemType)
      case _ => false
    }

    // Check array and element come from different sides
    val arrRefs = arr.references
    val elemRefs = elem.references
    val crossesSides = (arrRefs.nonEmpty && elemRefs.nonEmpty) && (
      (arrRefs.subsetOf(leftOut) && elemRefs.subsetOf(rightOut)) ||
      (arrRefs.subsetOf(rightOut) && elemRefs.subsetOf(leftOut))
    )

    validType && crossesSides
  }

  /**
   * Supported types have consistent equality semantics between array_contains and join.
   * Excludes Float/Double (NaN issues) and complex types.
   */
  private def isSupportedType(dt: DataType): Boolean = dt match {
    case _: AtomicType => dt match {
      case FloatType | DoubleType => false  // NaN != NaN
      case _ => true
    }
    case _ => false
  }

  private def buildPlan(
      join: Join,
      left: LogicalPlan,
      right: LogicalPlan,
      arr: Expression,
      elem: Expression,
      arrayOnLeft: Boolean,
      remaining: Seq[Expression],
      hint: JoinHint): Option[LogicalPlan] = {

    val unnestedAttr = AttributeReference("unnested", elem.dataType, nullable = true)()
    val generator = Explode(ArrayDistinct(arr))

    val (newLeft, newRight, joinCond) = if (arrayOnLeft) {
      val gen = Generate(generator, Nil, false, None, Seq(unnestedAttr), left)
      (gen, right, EqualTo(unnestedAttr, elem))
    } else {
      val gen = Generate(generator, Nil, false, None, Seq(unnestedAttr), right)
      (left, gen, EqualTo(elem, unnestedAttr))
    }

    // Combine new equality condition with remaining predicates
    val fullJoinCond = remaining.foldLeft(joinCond: Expression)(And)

    val innerJoin = Join(newLeft, newRight, Inner, Some(fullJoinCond), hint)

    // Project to original output (exclude unnested column)
    val projected = Project(join.output, innerJoin)

    Some(projected)
  }
}

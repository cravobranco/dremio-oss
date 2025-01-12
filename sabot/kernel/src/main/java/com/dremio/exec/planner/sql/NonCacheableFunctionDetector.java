/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.exec.planner.sql;

import com.google.common.collect.ImmutableSet;
import java.util.Locale;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

public final class NonCacheableFunctionDetector {
  /**
   * By its nature, Reflections are "eventually consistent" and optimizing "availability" at the
   * cost of "consistency". This is more of a side effect of CAP theorem, where you can only have 2
   * out of 3 and P is a must (users should be able to run their query whether a reflection is
   * available or not). You are left with a "dial" to tradeoff between "consistency" and
   * "availability". Consistency in this case would be to always read from the PDS / VDS directly
   * which has the latest data. Availability in this case would be to read from the Reflection,
   * which by definition always has "stale" data. A user can "tune" their consistency by picking how
   * often they want their refresh jobs to update. Now comes the notion of "dynamic" and
   * "non-deterministic functions" ... "non-deterministic" functions are functions whose output
   * changes everytime they are executed (values will differ across rows) "dynamic" functions are
   * functions that are dependent on the system context, but are consistent across rows in the same
   * query. By default, we can never cache the results of these functions, since they will differ
   * from execution to execution. The debate comes down to datetime functions like NOW() ... Suppose
   * you have a query that contains NOW() and you want to build a reflection off it, you have two
   * choices: 1) Reject the query outright 2) Accept the query with the idea that the data will be a
   * little "stale" (it was correct when the reflection was made and will be correct again when we
   * refresh the reflection) We are opting for option 2, since the user is already accepting "stale"
   * result by definition of going through a reflection for the sake of better availability. Now we
   * can't make the same choice for a function like "CURRENT_USER", since that isn't "stale" data
   * but rather "wrong" data (at no point in time were the results correct for a different user)
   * (it's also a security bug when users have different privileges).
   */
  private static final ImmutableSet<String> WHITELIST_NAMES =
      ImmutableSet.of(
              SqlStdOperatorTable.CURRENT_TIME,
              SqlStdOperatorTable.CURRENT_DATE,
              SqlStdOperatorTable.CURRENT_TIMESTAMP,
              SqlStdOperatorTable.LOCALTIME,
              SqlStdOperatorTable.LOCALTIMESTAMP,
              DremioSqlOperatorTable.NOW,
              DremioSqlOperatorTable.STATEMENT_TIMESTAMP,
              DremioSqlOperatorTable.TRANSACTION_TIMESTAMP,
              DremioSqlOperatorTable.CURRENT_TIME_UTC,
              DremioSqlOperatorTable.CURRENT_DATE_UTC,
              DremioSqlOperatorTable.CURRENT_TIMESTAMP_UTC,
              DremioSqlOperatorTable.TIMEOFDAY,
              DremioSqlOperatorTable.UNIX_TIMESTAMP)
          .stream()
          .map(SqlOperator::getName)
          .map(name -> name.toUpperCase(Locale.ENGLISH))
          .collect(ImmutableSet.toImmutableSet());

  public static Result detect(RelNode relNode) {
    RexShuttleImpl rexShuttle = new RexShuttleImpl();
    RexShuttleRelShuttle relShuttle = new RexShuttleRelShuttle(rexShuttle);
    relNode.accept(relShuttle);
    return rexShuttle.result;
  }

  public static Result detect(RexNode rexNode) {
    RexShuttleImpl rexShuttle = new RexShuttleImpl();
    rexNode.accept(rexShuttle);
    return rexShuttle.result;
  }

  public static Result detect(SqlOperator operator) {
    Result result = new Result();

    // Flatten Operator should be non-deterministic as reduce expression rule was replacing it as a
    // constant
    // expression and producing the wrong results. but, we want it to be cached, so we are making an
    // exception.
    // Eventually we are going to deprecate the flatten and will get rid of this.
    if (operator instanceof SqlFlattenOperator) {
      return result;
    }

    if (operator.isDynamicFunction() || !operator.isDeterministic()) {
      result.planCacheable = false;
    }

    if (operator.isDynamicFunction()) {
      result.reflectionIncrementalRefreshable = false;
    }

    if (operator.isDynamicFunction()
        && !WHITELIST_NAMES.contains(operator.getName().toUpperCase(Locale.ENGLISH))) {
      result.reflectionMatchable = false;
    }

    return result;
  }

  private static final class RexShuttleImpl extends RexShuttle {
    private final Result result;

    public RexShuttleImpl() {
      result = new Result();
    }

    @Override
    public RexNode visitCall(RexCall call) {
      boolean stateWontChange =
          !result.isPlanCacheable()
              && !result.reflectionMatchable
              && !result.reflectionIncrementalRefreshable;
      if (stateWontChange) {
        return call;
      }

      Result localResult = NonCacheableFunctionDetector.detect(call.getOperator());
      if (!localResult.reflectionMatchable) {
        result.reflectionMatchable = false;
      }

      if (!localResult.planCacheable) {
        result.planCacheable = false;
      }

      if (!localResult.reflectionIncrementalRefreshable) {
        result.reflectionIncrementalRefreshable = false;
      }

      return super.visitCall(call);
    }
  }

  public static class Result {
    private boolean planCacheable;
    private boolean reflectionMatchable;
    private boolean reflectionIncrementalRefreshable;

    public Result() {
      this.planCacheable = true;
      this.reflectionMatchable = true;
      this.reflectionIncrementalRefreshable = true;
    }

    public boolean isPlanCacheable() {
      return planCacheable;
    }

    public boolean isReflectionAllowed() {
      return reflectionMatchable;
    }

    public boolean isReflectionIncrementalRefreshable() {
      return reflectionIncrementalRefreshable;
    }
  }
}

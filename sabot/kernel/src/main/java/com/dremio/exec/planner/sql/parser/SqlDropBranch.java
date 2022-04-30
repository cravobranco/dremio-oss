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
package com.dremio.exec.planner.sql.parser;

import java.lang.reflect.Constructor;
import java.util.List;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SqlDirectHandler;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Implements SQL DROP BRANCH to drop a branch under a source. Represents statements like:
 * DROP BRANCH [ IF EXISTS ] branchName [ AT commitHash | FORCE ] IN source
 */
public final class SqlDropBranch extends SqlVersionBase {
  public static final SqlSpecialOperator OPERATOR =
      new SqlSpecialOperator("DROP_BRANCH", SqlKind.OTHER) {
        @Override
        public SqlCall createCall(
            SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
          Preconditions.checkArgument(
              operands.length == 5, "SqlDropBranch.createCall() has to get 2 operands!");
          return new SqlDropBranch(
              pos,
              (SqlLiteral) operands[0],
              (SqlIdentifier) operands[1],
              (SqlIdentifier) operands[2],
              (SqlLiteral) operands[3],
              (SqlIdentifier) operands[4]);
        }
      };

  private final SqlLiteral existenceCheck;
  private final SqlIdentifier branchName;
  private final SqlIdentifier commitHash;
  private final SqlLiteral forceDrop;

  public SqlDropBranch(
      SqlParserPos pos,
      SqlLiteral existenceCheck,
      SqlIdentifier branchName,
      SqlIdentifier commitHash,
      SqlLiteral forceDrop,
      SqlIdentifier source) {
    super(pos, source);
    this.branchName = branchName;
    this.existenceCheck = existenceCheck;
    this.commitHash = commitHash;
    this.forceDrop = forceDrop;
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    List<SqlNode> ops = Lists.newArrayList();
    ops.add(existenceCheck);
    ops.add(branchName);
    ops.add(commitHash);
    ops.add(forceDrop);
    ops.add(getSourceName());

    return ops;
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    writer.keyword("DROP");
    writer.keyword("BRANCH");

    if (existenceCheck.booleanValue()) {
      writer.keyword("IF");
      writer.keyword("EXISTS");
    }

    branchName.unparse(writer, leftPrec, rightPrec);

    if (commitHash != null) {
      writer.keyword("AT");
      commitHash.unparse(writer, leftPrec, rightPrec);
    } else if (forceDrop.booleanValue()) {
      writer.keyword("FORCE");
    }

    writer.keyword("IN");
    getSourceName().unparse(writer, leftPrec, rightPrec);
  }

  @Override
  public SqlDirectHandler<?> toDirectHandler(QueryContext context) {
    try {
      final Class<?> cl = Class.forName("com.dremio.exec.planner.sql.handlers.DropBranchHandler");
      final Constructor<?> ctor = cl.getConstructor(QueryContext.class);

      return (SqlDirectHandler<?>) ctor.newInstance(context);
    } catch (ClassNotFoundException e) {
      throw UserException.unsupportedError(e)
          .message("DROP BRANCH action is not supported.")
          .buildSilently();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  public SqlLiteral getExistenceCheck() {
    return existenceCheck;
  }

  public SqlIdentifier getBranchName() {
    return branchName;
  }

  public SqlIdentifier getCommitHash() {
    return commitHash;
  }

  public SqlLiteral getForceDrop() {
    return forceDrop;
  }
}
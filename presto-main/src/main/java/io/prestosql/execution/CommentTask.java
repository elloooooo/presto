/*
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
package io.prestosql.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.TableHandle;
import io.prestosql.security.AccessControl;
import io.prestosql.sql.tree.Comment;
import io.prestosql.sql.tree.Expression;
import io.prestosql.transaction.TransactionManager;

import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.prestosql.metadata.MetadataUtil.createQualifiedObjectName;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.StandardErrorCode.TABLE_NOT_FOUND;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;

public class CommentTask
        implements DataDefinitionTask<Comment>
{
    @Override
    public String getName()
    {
        return "COMMENT";
    }

    @Override
    public ListenableFuture<?> execute(Comment statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, QueryStateMachine stateMachine, List<Expression> parameters)
    {
        Session session = stateMachine.getSession();

        if (statement.getType() == Comment.Type.TABLE) {
            QualifiedObjectName tableName = createQualifiedObjectName(session, statement, statement.getName());
            Optional<TableHandle> tableHandle = metadata.getTableHandle(session, tableName);
            if (tableHandle.isEmpty()) {
                throw semanticException(TABLE_NOT_FOUND, statement, "Table does not exist: %s", tableName);
            }

            accessControl.checkCanSetTableComment(session.toSecurityContext(), tableName);

            metadata.setTableComment(session, tableHandle.get(), statement.getComment());
        }
        else {
            throw semanticException(NOT_SUPPORTED, statement, "Unsupported comment type: %s", statement.getType());
        }

        return immediateFuture(null);
    }
}

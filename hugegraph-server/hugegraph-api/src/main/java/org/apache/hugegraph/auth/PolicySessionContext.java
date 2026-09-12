/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.auth;

import org.apache.hugegraph.auth.HugeAuthenticator.User;
import org.apache.hugegraph.task.TaskManager;

/** A session identity never comes from the event-loop thread's ambient state. */
final class PolicySessionContext {

    private final User owner;

    PolicySessionContext(User owner) {
        this.owner = owner;
    }

    void run(Runnable action) {
        String auth = AuthContext.getContext();
        String task = TaskManager.getContext();
        String space = HugeGraphAuthProxy.getRequestGraphSpace();
        HugeGraphAuthProxy.Context previous = HugeGraphAuthProxy.setContext(null);
        try {
            HugeGraphAuthProxy.resetContext();
            TaskManager.resetContext();
            AuthContext.setContext(this.owner.toJson());
            action.run();
        } finally {
            HugeGraphAuthProxy.resetContext();
            if (previous != null) {
                HugeGraphAuthProxy.setContext(previous);
            }
            if (auth != null) {
                AuthContext.setContext(auth);
            }
            if (task == null) {
                TaskManager.resetContext();
            } else {
                TaskManager.setContext(task);
            }
            if (space != null) {
                HugeGraphAuthProxy.setRequestGraphSpace(space);
            }
        }
    }
}

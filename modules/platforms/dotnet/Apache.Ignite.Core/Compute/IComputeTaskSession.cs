/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Apache.Ignite.Core.Compute
{
    using System.Collections.Generic;

    /// <summary>
    /// Stores custom compute task attributes. Specific compute task implementations must be annotated with the
    /// <see cref="ComputeTaskSessionFullSupportAttribute"/> to enable distributing the task attributes to the compute
    /// jobs that the task creates.
    /// </summary>
    public interface IComputeTaskSession
    {
        /// <summary>
        /// Gets the value of the given key or <c>null</c> if the key does not exist.
        /// </summary>
        TV GetAttribute<TK, TV>(TK key);

        /// <summary>
        /// Stores the collection of attributes.
        /// </summary>
        void SetAttributes<TK, TV>(params KeyValuePair<TK, TV>[] attrs);
    }
}
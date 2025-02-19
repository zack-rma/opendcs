/*
 * Copyright 2025 OpenDCS Consortium and its Contributors
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.opendcs.database.api;


import java.util.Optional;

import org.opendcs.settings.api.OpenDcsSettings;

/**
 * Basic interface of an "OpenDCS Database interface.""
 */
public interface OpenDcsDatabase
{
	/**
	 * Retrieve an instance of the given legacy database type.
	 * @param <T> Type of Legacy Database. `decodes.sql.Database` or `TimeSeriesDb` or one of its derivatives.
	 * @param legacyDatabaseType class reference to the desired database type.
	 * @return Optional&lt;T&gt; that contains the Instance, or empty if not available.
	 * @deprecated This is provided for transition, new implementations should return an empty optional.
	 */
	@Deprecated
	<T> Optional<T> getLegacyDatabase(Class<T> legacyDatabaseType);

	/**
	 * Retrieve DAO from the database
	 * @param <T> DAO Type
	 * @param dao Dao Class
	 * @return A valid instance for this database, or an empty optional if the DAO is not supported
	 */
	<T extends OpenDcsDao> Optional<T> getDao(Class<T> dao);

	/**
	 * Start a new transaction to perform data source operations.
	 * @return a valid DataTransaction containing any connections required to perform operations
	 * @throws OpenDcsDataException if any issues creating the transaction.
	 */
	DataTransaction newTransaction() throws OpenDcsDataException;

	/**
	 * Retrieve Settings of a given type. All implementations must provide "DecodesSettings".
	 * Implementations may determine if a given set of settings are immutable at runtime.
	 * @param <T> Type of settings. Currently implemented is "DecodesSettings"
	 * @param settingsClass type of settings to get
	 * @return Optional&lt;T&gt; Settings if available, otherwise empty.
	 */
	<T extends OpenDcsSettings> Optional<T> getSettings(Class<T> settingsClass);
}
/*
 * Copyright (C) 2016  Matteo Morena
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package mamo.vanillaVotifier;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import mamo.vanillaVotifier.exception.InvalidPrivateKeyFileException;
import mamo.vanillaVotifier.exception.InvalidPublicKeyFileException;
import mamo.vanillaVotifier.exception.PrivateKeyFileNotFoundException;
import mamo.vanillaVotifier.exception.PublicKeyFileNotFoundException;
import mamo.vanillaVotifier.util.JsonUtils;
import mamo.vanillaVotifier.util.RsaUtils;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.util.io.pem.PemWriter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

@Deprecated
public class JsonConfig extends AbstractConfig {
	public JsonConfig(File configFile) {
		this.configFile = configFile;
	}

	@Override
	public synchronized void load() throws IOException, InvalidKeySpecException {
		if (!configFile.exists()) {
			BufferedInputStream in = new BufferedInputStream(JsonConfig.class.getResourceAsStream("config.json"));
			StringBuilder stringBuilder = new StringBuilder();
			int i;
			while ((i = in.read()) != -1) {
				stringBuilder.append((char) i);
			}
			BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(configFile));
			for (char c : stringBuilder.toString().replaceAll("\\u000D\\u000A|[\\u000A\\u000B\\u000C\\u000D\\u0085\\u2028\\u2029]", System.getProperty("line.separator")).toCharArray()) {
				out.write((int) c);
			}
			out.flush();
			out.close();
		}
		BufferedInputStream in = new BufferedInputStream(JsonConfig.class.getResourceAsStream("config.json"));
		JSONObject defaultConfig = new JSONObject(new JSONTokener(in));
		in.close();
		JSONObject config = new JSONObject(new JSONTokener(new BufferedInputStream(new FileInputStream(configFile))));
		boolean save = JsonUtils.merge(defaultConfig, config);
		configVersion = config.getInt("config-version");
		if (configVersion == 2) {
			v2ToV3(config);
			configVersion = 3;
			save = true;
		}
		logFile = new File(config.getString("log-file"));
		inetSocketAddress = new InetSocketAddress(config.getString("ip"), config.getInt("port"));
		publicKeyFile = new File(config.getJSONObject("key-pair-files").getString("public"));
		privateKeyFile = new File(config.getJSONObject("key-pair-files").getString("private"));
		if (!publicKeyFile.exists() && !privateKeyFile.exists()) {
			KeyPair keyPair = RsaUtils.genKeyPair();
			PemWriter publicPemWriter = new PemWriter(new BufferedWriter(new FileWriter(publicKeyFile)));
			publicPemWriter.writeObject(new PemObject("PUBLIC KEY", keyPair.getPublic().getEncoded()));
			publicPemWriter.flush();
			publicPemWriter.close();
			PemWriter privatePemWriter = new PemWriter(new BufferedWriter(new FileWriter(privateKeyFile)));
			privatePemWriter.writeObject(new PemObject("RSA PRIVATE KEY", keyPair.getPrivate().getEncoded()));
			privatePemWriter.flush();
			privatePemWriter.close();
		}
		if (!publicKeyFile.exists()) {
			throw new PublicKeyFileNotFoundException();
		}
		if (!privateKeyFile.exists()) {
			throw new PrivateKeyFileNotFoundException();
		}
		PemReader publicKeyPemReader = new PemReader(new BufferedReader(new FileReader(publicKeyFile)));
		PemReader privateKeyPemReader = new PemReader(new BufferedReader(new FileReader(privateKeyFile)));
		PemObject publicPemObject = publicKeyPemReader.readPemObject();
		if (publicPemObject == null) {
			throw new InvalidPublicKeyFileException();
		}
		PemObject privatePemObject = privateKeyPemReader.readPemObject();
		if (privatePemObject == null) {
			throw new InvalidPrivateKeyFileException();
		}
		keyPair = new KeyPair(RsaUtils.bytesToPublicKey(publicPemObject.getContent()), RsaUtils.bytesToPrivateKey(privatePemObject.getContent()));
		publicKeyPemReader.close();
		privateKeyPemReader.close();
		rconConfigs = new ArrayList<RconConfig>();
		for (int i = 0; i < config.getJSONArray("rcon-list").length(); i++) {
			JSONObject jsonObject = config.getJSONArray("rcon-list").getJSONObject(i);
			RconConfig rconConfig = new RconConfig(new InetSocketAddress(jsonObject.getString("ip"), jsonObject.getInt("port")), jsonObject.getString("password"));
			for (int j = 0; j < jsonObject.getJSONArray("commands").length(); j++) {
				rconConfig.getCommands().add(jsonObject.getJSONArray("commands").getString(j));
			}
			rconConfigs.add(rconConfig);
		}
		loaded = true;
		if (save) {
			save();
		}
	}

	private void v2ToV3(JSONObject jsonObject) {
		if (!jsonObject.has("commands")) {
			jsonObject.put("commands", new JSONArray());
		}
		if (!jsonObject.has("rcon-list")) {
			jsonObject.put("rcon-list", new JSONArray() {
				{
					put(new JSONObject());
				}
			});
		}
		jsonObject.getJSONArray("rcon-list").put(jsonObject.get("rcon-list"));
		jsonObject.getJSONArray("rcon-list").getJSONObject(0).put("commands", jsonObject.get("commands"));
		jsonObject.remove("commands");
		jsonObject.put("config-version", 3);
	}

	@Override
	public synchronized void save() throws IOException {
		JSONObject config = new JSONObject();
		config.put("config-version", getConfigVersion());
		config.put("log-file", getLogFile().getPath());
		config.put("ip", getInetSocketAddress().getAddress().toString());
		config.put("port", getInetSocketAddress().getPort());
		config.put("key-pair-files", new JSONObject() {
			{
				put("public", getPublicKeyFile().getPath());
				put("private", getPrivateKeyFile().getPath());
			}
		});
		config.put("rcon-list", new JSONArray() {
			{
				for (final RconConfig rconConfig : getRconConfigs()) {
					put(new JSONObject() {
						{
							put("ip", rconConfig.getInetSocketAddress().getAddress().toString());
							put("port", rconConfig.getInetSocketAddress().getPort());
							put("password", rconConfig.getPassword());
							put("commands", rconConfig.getCommands());
						}
					});
				}
			}
		});
		BufferedWriter out = new BufferedWriter(new FileWriter(configFile));
		out.write(JsonUtils.jsonToPrettyString(config));
		out.flush();
		out.close();
		PemWriter publicPemWriter = new PemWriter(new BufferedWriter(new FileWriter(getPublicKeyFile())));
		publicPemWriter.writeObject(new PemObject("PUBLIC KEY", getKeyPair().getPublic().getEncoded()));
		publicPemWriter.flush();
		publicPemWriter.close();
		PemWriter privatePemWriter = new PemWriter(new BufferedWriter(new FileWriter(getPrivateKeyFile())));
		privatePemWriter.writeObject(new PemObject("RSA PRIVATE KEY", getKeyPair().getPrivate().getEncoded()));
		privatePemWriter.flush();
		privatePemWriter.close();
	}
}
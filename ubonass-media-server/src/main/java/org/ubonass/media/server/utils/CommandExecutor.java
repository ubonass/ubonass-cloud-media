/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.ubonass.media.server.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class CommandExecutor {

    public static String execCommand(String... command) throws IOException, InterruptedException {

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder processOutput = new StringBuilder();

        try (BufferedReader processOutputReader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));) {

            String readLine;
            while ((readLine = processOutputReader.readLine()) != null) {
                processOutput.append(readLine + System.lineSeparator());
            }
            process.waitFor();
        }

        return processOutput.toString().trim();
    }

}

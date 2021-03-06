#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import time
from unittest import TestCase

import mock
from mesos.interface import mesos_pb2

from apache.aurora.executor.common.status_checker import StatusChecker, StatusResult
from apache.aurora.executor.status_manager import StatusManager


class FakeStatusChecker(StatusChecker):
  def __init__(self):
    self.call_count = 0

  @property
  def status(self):
    status_result = None
    if self.call_count == 1:
      status_result = StatusResult('Task is healthy.', mesos_pb2.TASK_RUNNING)
    if self.call_count == 2:
      status_result = StatusResult('Task is healthy.', mesos_pb2.TASK_RUNNING)
    if self.call_count == 3:
      status_result = StatusResult('Reason why a task failed.', mesos_pb2.TASK_KILLED)
    self.call_count += 1
    return status_result


class TestStatusManager(TestCase):
  def setUp(self):
    self.callback_call_count = 0

  def test_run(self):
    checker = FakeStatusChecker()
    def running(result):
      self.callback_call_count += 1
    def shutdown(result):
      self.callback_call_count += 1
    mock_time = mock.create_autospec(spec=time, instance=True)
    status_manager = StatusManager(checker, running, shutdown, mock_time)
    status_manager.run()
    assert mock_time.sleep.call_count == 3
    assert self.callback_call_count == 2

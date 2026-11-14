'use strict';

// Bind device commands to the live DSH turn, independent of message content.
function currentTurnId(session) {
  for (let index = session.seq - 1; index >= 0; index--) {
    const event = session.eventAt(index);
    if (event.type === 'turn/start') return index;
    if (event.type === 'turn/end') break;
  }
  return -1;
}

module.exports = {currentTurnId};

// Public Lyricon subscriber wire contract. Apache-2.0, Proify / Tomakino.
package io.github.proify.lyricon.subscriber;
import io.github.proify.lyricon.subscriber.IActivePlayerListener;
import android.os.SharedMemory;
interface IRemoteService {
    void setActivePlayerListener(in IActivePlayerListener listener);
    SharedMemory getActivePlayerPositionMemory();
    void disconnect();
}

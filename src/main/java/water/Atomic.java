package water;

import water.DTask;

/**
 * Atomic update of a Key
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public abstract class Atomic extends DTask {
  public Key _key;              // Transaction key

  // User's function to be run atomically.  The Key's Value is fetched from the
  // home STORE and passed in.  The returned Value is atomically installed as
  // the new Value (and the function is retried until it runs atomically).  The
  // original Value is supposed to be read-only.  If the original Key misses
  // (no Value), one is created with 0 length and wrong Value._type to allow
  // the Key to passed in (as part of the Value) 
  abstract public Value atomic( Value val );

  /** Executed on the transaction key's <em>home</em> node after any successful
   *  atomic update.  Override this if you need to perform some action after
   *  the update succeeds (eg cleanup).
   */
  public void onSuccess(){}

  // Only invoked remotely; this is now the key's home and can be directly executed
  @Override public final Atomic dinvoke( H2ONode sender ) {  compute2(); return this; }

  /** Block until it completes, even if run remotely */
  public final Atomic invoke( Key key ) {
    RPC<Atomic> rpc = fork(key);
    if( rpc != null ) rpc.get(); // Block for it
    return this;
  }

  // Fork off
  public final RPC<Atomic> fork(Key key) {
    _key = key;
    if( key.home() ) {          // Key is home?
      compute2();               // Also, run it blocking/now
      return null;
    } else {                    // Else run it remotely
      return RPC.call(key.home_node(),this);
    }
  }

  // The (remote) workhorse:
  @Override public final void compute2( ) {
    assert _key.home();         // Key is at Home!
    Futures fs = new Futures(); // Must block on all invalidates eventually
    Value val1 = DKV.get(_key);
    while( true ) {
      // Run users' function.  This is supposed to read-only from val1 and
      // return new val2 to atomically install.
      Value val2 = atomic(val1);
      if( val2 == null ) break; // ABORT: they gave up
      assert val1 != val2;      // No returning the same Value
      // Attempt atomic update
      Value res = DKV.DputIfMatch(_key,val2,val1,fs);
      if( res == val1 ) {       // Success?
        onSuccess();            // Call user's post-XTN function
        fs.blockForPending();   // Block for any pending invalidates on the atomic update
        break;
      }
      val1 = res;               // Otherwise try again with the current value
    }                           // and retry
    _key = null;                // No need for key no more, don't send it back
    tryComplete();              // Tell F/J this task is done
  }

  @Override public byte priority() { return H2O.ATOMIC_PRIORITY; }
}

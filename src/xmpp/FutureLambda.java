package xmpp;

import java.util.function.Consumer;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class FutureLambda<T extends Future<?>> implements GenericFutureListener<T> {
	public static class VoidFL extends FutureLambda<Future<Void>> {
		public VoidFL(Consumer<Future<Void>> lambda) {
			super(lambda);
		}
		
	}
	
	private Consumer<T> lambda;
	
	public FutureLambda(Consumer<T> lambda) {
		this.lambda = lambda;
	}
	
	@Override
	public void operationComplete(T f) throws Exception {
		lambda.accept(f);
	}
}

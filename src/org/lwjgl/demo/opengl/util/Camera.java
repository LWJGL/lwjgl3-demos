/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A simple pinhole camera.
 *
 * @author Kai Burjack
 */
public class Camera {

	private final Matrix4f projectionMatrix = new Matrix4f();
	private final Matrix4f viewMatrix = new Matrix4f();
	private final Matrix4f projectionViewMatrix = new Matrix4f();
	private final Matrix4f invViewProjectionMatrix = new Matrix4f();
	private final Vector3f position = new Vector3f();
	private boolean refreshInverseMatrix;
	private boolean refreshProjectionViewMatrix;

	/**
	 * Compute the world direction vector based on the given X and Y coordinates
	 * in normalized-device space.
	 *
	 * @param x
	 *            the X coordinate within [-1..1]
	 * @param y
	 *            the Y coordinate within [-1..1]
	 * @param res
	 *            will contain the result
	 */
	public void getEyeRay(float x, float y, Vector3f res) {
		if (refreshProjectionViewMatrix) {
			projectionViewMatrix.set(projectionMatrix).mul(viewMatrix);
			refreshProjectionViewMatrix = false;
		}
		projectionViewMatrix.frustumRayDir((x + 1) * 0.5f, (y + 1) * 0.5f, res);
	}

	/**
	 * {@inheritDoc}
	 */
	public void setLookAt(Vector3f position, Vector3f lookAt, Vector3f up) {
		viewMatrix.setLookAt(position, lookAt, up);
		this.position.set(position);
		refreshInverseMatrix = true;
		refreshProjectionViewMatrix = true;
	}

	public Vector3f getPosition() {
		return position;
	}

	public Matrix4f getProjectionMatrix() {
		return projectionMatrix;
	}

	public Matrix4f getViewMatrix() {
		return viewMatrix;
	}

	public void setFrustumPerspective(float fovY, float aspect, float near, float far) {
		projectionMatrix.setPerspective((float) Math.toRadians(fovY), aspect, near, far);
		refreshInverseMatrix = true;
		refreshProjectionViewMatrix = true;
	}

	public Matrix4f getInverseProjectionViewMatrix() {
		if (refreshInverseMatrix) {
			invViewProjectionMatrix.set(projectionMatrix).mul(viewMatrix).invert();
			refreshInverseMatrix = false;
		}
		return invViewProjectionMatrix;
	}

}